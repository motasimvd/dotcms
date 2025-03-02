package com.dotcms.csspreproc;

import com.dotcms.csspreproc.CachedCSS.ImportedAsset;
import com.dotcms.util.DownloadUtil;
import com.dotmarketing.beans.Host;
import com.dotmarketing.beans.Identifier;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.CacheLocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.business.web.WebAPILocator;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DotHibernateException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.portlets.contentlet.model.ContentletVersionInfo;
import com.dotmarketing.portlets.fileassets.business.FileAsset;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.InodeUtils;
import com.dotmarketing.util.Logger;
import com.liferay.portal.model.User;
import com.liferay.util.StringPool;
import org.apache.http.HttpStatus;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Optional;

/**
 * This Servlet handles all the requests related to compiling SCSS Files in dotCMS. It takes care of tasks such as:
 * <ul>
 *     <li>Verifying sure that the incoming SCSS file actually exists and is available to be processed by the appropriate
 *     implementation of the SASS compiler.</li>
 *     <li>Generating the contents of the resulting CSS file based on the results of the compilation.</li>
 *     <li>Generating the appropriate response in case a major problem occurred.</li>
 * </ul>
 * Keep in mind that the Servlet <b>will NOT fail in case a SCSS file cannot be compiled</b> as dotCMS must carry on with
 * the HTML Page rendering process.
 *
 * @author Jorge Urdaneta
 * @since Jan 23rd, 2014
 */
public class CSSPreProcessServlet extends HttpServlet {

    private static final long serialVersionUID = -3315180323197314439L;

    @Override
    public void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        String actualUri = StringPool.BLANK;
        try {
            final Host host = WebAPILocator.getHostWebAPI().getCurrentHost(req);
            final boolean live = !WebAPILocator.getUserWebAPI().isLoggedToBackend(req);
            final User user = WebAPILocator.getUserWebAPI().getLoggedInUser(req);
            final String origURI=req.getRequestURI();
            final String fileUri = origURI.replace("/DOTSASS","");
            final DotLibSassCompiler compiler = new DotLibSassCompiler(host, fileUri, live, req);
            
            // check if the asset exists
            actualUri =  fileUri.substring(0, fileUri.lastIndexOf('.')) + "." + compiler.getDefaultExtension();
            final Identifier ident = APILocator.getIdentifierAPI().find(host, actualUri);
            if(ident==null || !InodeUtils.isSet(ident.getId())) {
                resp.sendError(HttpStatus.SC_NOT_FOUND);
                return;
            }
            
            // get the asset in order to build etag and check permissions
            final long defLang=APILocator.getLanguageAPI().getDefaultLanguage().getId();
            FileAsset fileasset;
            try {
                fileasset = APILocator.getFileAssetAPI().fromContentlet(
                    APILocator.getContentletAPI().findContentletByIdentifier(ident.getId(), live, defLang, user, true));
            } catch (final DotSecurityException ex) {
                resp.sendError(HttpStatus.SC_FORBIDDEN);
                return;
            }
            
            boolean userHasEditPerms = false;
            if(!live) {
                userHasEditPerms = APILocator.getPermissionAPI().doesUserHavePermission(fileasset,PermissionAPI.PERMISSION_EDIT,user);
                if(req.getParameter("recompile")!=null && userHasEditPerms) {
                    CacheLocator.getCSSCache().remove(host.getIdentifier(), actualUri, false);
                    CacheLocator.getCSSCache().remove(host.getIdentifier(), actualUri, true);
                }
            }
            
            CachedCSS cache = CacheLocator.getCSSCache().get(host.getIdentifier(), actualUri, live, user);
            
            byte[] responseData=null;
            Date cacheMaxDate=null;
            CachedCSS cacheObject=null;
            
            if(cache==null || cache.data==null) {
                // do compile!
                synchronized(ident.getId().intern()) {
                    cache = CacheLocator.getCSSCache().get(host.getIdentifier(), actualUri, live, user);
                    if(cache==null || cache.data==null) {
                        Logger.debug(this, "compiling css data for "+host.getHostname()+":"+fileUri);
                        
                        try {
                            compiler.compile();
                        } catch (Throwable ex) {
                          Logger.error(this, "Error compiling " + host.getHostname() + ":" + fileUri, ex);
                          if (Config.getBooleanProperty("SHOW_SASS_ERRORS_ON_FRONTEND", true)) {
                            if(userHasEditPerms) {
                              ex = (ex.getCause()!=null) ? ex.getCause() : ex;
                              resp.getWriter().print("<html><body><h2>Error compiling sass</h2><p>(this only shows if you are an editor in dotCMS)</p><pre>");
                              ex.printStackTrace(resp.getWriter());
                              resp.getWriter().print("</pre></body></html>");
                            }
                          }
                          throw new Exception(ex);
                      }
                        
                        // build cache object
                        final Optional<ContentletVersionInfo> vinfo = APILocator.getVersionableAPI()
                                .getContentletVersionInfo(ident.getId(), defLang);

                        if(!vinfo.isPresent()) {
                            resp.sendError(HttpStatus.SC_NOT_FOUND);
                            return;
                        }

                        final CachedCSS newcache = new CachedCSS();
                        newcache.data = compiler.getOutput();
                        newcache.hostId = host.getIdentifier();
                        newcache.uri = actualUri;
                        newcache.live = live;
                        newcache.modDate = vinfo.get().getVersionTs();
                        newcache.imported = new ArrayList<>();
                        for(String importUri : compiler.getAllImportedURI()) {
                            // newcache entry for the imported asset
                            final ImportedAsset asset = new ImportedAsset();
                            asset.uri = importUri;
                            Identifier importUriIdentifier;
                            if(importUri.startsWith("//")) {
                                importUri=importUri.substring(2);
                                final String hn=importUri.substring(0, importUri.indexOf('/'));
                                final String uu=importUri.substring(importUri.indexOf('/'));
                                importUriIdentifier = APILocator.getIdentifierAPI().find(APILocator.getHostAPI().findByName(hn, user, live),uu);
                            }
                            else {
                                importUriIdentifier = APILocator.getIdentifierAPI().find(host, importUri);
                            }
                            final Optional<ContentletVersionInfo> impInfo = APILocator.getVersionableAPI()
                                    .getContentletVersionInfo(importUriIdentifier.getId(), defLang);

                            if(!impInfo.isPresent()) {
                                resp.sendError(HttpStatus.SC_NOT_FOUND);
                                return;
                            }

                            asset.modDate = impInfo.get().getVersionTs();
                            newcache.imported.add(asset);
                            Logger.debug(this, host.getHostname()+":"+actualUri+" imports-> "+importUri);
                            
                            // actual cache entry for the imported asset. If needed
                            synchronized(importUriIdentifier.getId().intern()) {
                                if(CacheLocator.getCSSCache().get(importUriIdentifier.getHostId(), importUri, live, user)==null) {
                                    CachedCSS entry = new CachedCSS();
                                    entry.data = null;
                                    entry.hostId = importUriIdentifier.getHostId();
                                    entry.imported = new ArrayList<>();
                                    entry.live = live;
                                    entry.modDate = impInfo.get().getVersionTs();
                                    entry.uri = importUri;
                                    CacheLocator.getCSSCache().add(entry);
                                }
                            }
                        }
                        CacheLocator.getCSSCache().add(newcache);
                        cacheMaxDate = newcache.getMaxDate();
                        cacheObject = newcache;
                        responseData = compiler.getOutput();
                    }
                }
            }
            
            if(responseData==null) {
                if(cache!=null && cache.data!=null) {
                    // if css is cached an valid is used as response
                    responseData = cache.data;
                    cacheMaxDate = cache.getMaxDate();
                    cacheObject = cache;
                    Logger.debug(this, "using cached css data for "+host.getHostname()+":"+fileUri);
                } else {
                    resp.sendError(HttpStatus.SC_INTERNAL_SERVER_ERROR, "no data!");
                    return;
                }
            }
            boolean doDownload = true;
            
            if(live) {
                // we use etag dot:inode:cacheMaxDate:filesize and lastMod:cacheMaxDate
                // so the browser downloads it if any of the imported files changes
                doDownload = DownloadUtil.isModifiedEtag(req, resp, fileasset.getInode(), 
                        cacheMaxDate.getTime(), fileasset.getFileSize());
            }
            
            if(doDownload) {
                // write the actual response to the user
                resp.setContentType("text/css");
                resp.setHeader("Content-Disposition", 
                        "inline; filename=\"" + fileUri.substring(fileUri.lastIndexOf('/'), fileUri.length()) + "\"");
                
                if(!live && userHasEditPerms && req.getParameter("debug")!=null) {
                    // debug information requested
                    final PrintWriter out = resp.getWriter();
                    out.println("/*");
                    out.println("Cached CSS: "+host.getHostname()+":"+actualUri);
                    out.println("Size: "+cacheObject.data.length+" bytes");
                    out.println("Imported uris:");
                    for (final ImportedAsset asset : cacheObject.imported) {
                        out.println("  "+asset.uri);
                    }
                    out.println("*/");
                    out.println(new String(responseData));
                } else {
                    resp.getOutputStream().write(responseData);
                }
            }
        } catch (final Exception ex) {
        	try {
				final Class clazz = Class.forName("org.apache.catalina.connector.ClientAbortException");
				if(ex.getClass().equals(clazz)){
					Logger.debug(this, "ClientAbortException while serving compiled css file:" + ex.getMessage(), ex);
				} else {
                    Logger.error(this, String.format("Exception while serving compiled css file '%s' : %s", actualUri
                            , ex.getMessage()), ex);
				}
			//if we are not running on tomcat
			} catch (final ClassNotFoundException e) {
                Logger.error(this, String.format("Exception while serving compiled css file '%s' : %s", actualUri,
                        ex.getMessage()), ex);
			}
        } finally {
            try {
                HibernateUtil.closeSession();
            } catch (final DotHibernateException e) {
                Logger.warn(this, "Exception while hibernate session close",e);
            }
        }
    }

}
