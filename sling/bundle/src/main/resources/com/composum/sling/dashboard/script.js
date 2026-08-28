
class DashboardPanel extends ViewWidget {

    static selector = '.dashboard-page_dashboard';

    constructor(element) {
        super(element);
        this.profile = new Profile('dashboard');
        this.currentView = this.profile.get('currentView');
        this.$el.find('.composum-tools_widget > .composum-tools_widget_tile-link').on('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            const href = $(event.target).attr('href');
            this.openView(href);
            return false;
        });
        this.openView(this.currentView);
    }

    openView(href) {
        if (href) {
            $.ajax({
                type: 'GET',
                url: href,
                success: function (content) {
                    this.setCurrentView(href);
                    this.$el.html(content);
                    this.$el.parent().addClass('view-visible');
                    this.onContentLoaded(undefined, this.$el);
                    this.$el.find('.view-head').on('click', (event) => {
                        this.setCurrentView('');
                        window.location.reload();
                    });
                }.bind(this),
                async: true,
                cache: false
            });
            this.$el.html()
        }
    }

    setCurrentView(href) {
        this.currentView = href;
        this.profile.set('currentView', href || '');
    }
}

CPM.widgets.register(DashboardPanel);
