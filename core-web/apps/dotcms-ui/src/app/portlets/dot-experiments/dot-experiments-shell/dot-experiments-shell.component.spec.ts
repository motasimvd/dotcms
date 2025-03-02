import { DotExperimentsShellComponent } from './dot-experiments-shell.component';
import { createComponentFactory, Spectator } from '@ngneat/spectator';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { DotExperimentsUiHeaderComponent } from '@portlets/dot-experiments/shared/ui/experiments-header/dot-experiments-ui-header.component';
import { DotLoadingIndicatorModule } from '@components/_common/iframe/dot-loading-indicator/dot-loading-indicator.module';
import { ComponentStore } from '@ngrx/component-store';
import { Toast, ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';

class ActivatedRouteMock {
    get parent() {
        return {
            parent: {
                snapshot: {
                    data: {
                        content: {
                            page: {
                                identifier: '1234',
                                title: 'My dotCMS experiment'
                            }
                        }
                    }
                }
            }
        };
    }
}

class RouterMock {
    navigate() {
        return true;
    }
}

describe('DotExperimentsShellComponent', () => {
    let spectator: Spectator<DotExperimentsShellComponent>;
    let dotExperimentsUiHeaderComponent: DotExperimentsUiHeaderComponent;
    let toastComponent: Toast;

    const createComponent = createComponentFactory({
        imports: [
            HttpClientTestingModule,
            DotExperimentsUiHeaderComponent,
            DotLoadingIndicatorModule,
            RouterModule,
            ToastModule
        ],
        component: DotExperimentsShellComponent,
        providers: [
            ComponentStore,
            MessageService,
            {
                provide: ActivatedRoute,
                useClass: ActivatedRouteMock
            },
            {
                provide: Router,
                useClass: RouterMock
            }
        ]
    });

    beforeEach(() => {
        spectator = createComponent();
    });

    it('should has DotExperimentHeaderComponent', () => {
        toastComponent = spectator.query(Toast);

        expect(toastComponent).toExist();
    });
    it('should has DotExperimentHeaderComponent', () => {
        const page = new ActivatedRouteMock().parent.parent.snapshot.data.content.page;
        dotExperimentsUiHeaderComponent = spectator.query(DotExperimentsUiHeaderComponent);

        expect(dotExperimentsUiHeaderComponent).toExist();
        expect(dotExperimentsUiHeaderComponent.title).toBe(page.title);
    });

    it('should call Navegate when click back', () => {
        const router = spectator.inject(Router);
        const navigateSpy = spyOn(router, 'navigate');

        spectator.component.goBack();

        expect(navigateSpy).toHaveBeenCalledWith(['edit-page/content'], {
            queryParamsHandling: 'preserve'
        });
    });
});
