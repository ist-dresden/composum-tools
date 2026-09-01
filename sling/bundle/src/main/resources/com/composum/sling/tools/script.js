class Profile {

    static KEY = 'composum-tools';

    constructor(aspect) {
        this.aspect = aspect;
        this.load();
    }

    load() {
        this.profile = JSON.parse(localStorage.getItem(Profile.KEY) || '{}');
        if (!this.profile[this.aspect]) {
            this.profile[this.aspect] = {};
        }
    }

    get(key) {
        return this.profile[this.aspect][key];
    }

    set(key, value) {
        this.load();
        this.profile[this.aspect][key] = value;
        localStorage.setItem(Profile.KEY, JSON.stringify(this.profile));
    }
}

class URL {

    constructor(url) {
        const parts = /^((https?:\/\/[^/]+)?((\/[^/?]+)*\/([^/?]*)))(\?([^?]*))?$/i.exec(url);
        this.uri = parts[1];
        this.server = parts[2] || '';
        this.path = parts[3] || '/';
        this.name = parts[5];
        this.query = parts[7] || '';
        this.parameters = URL.parameters(this.query);
    }

    static parameters(query) {
        const params = {};
        if (query) {
            query.split('&').forEach(function (param) {
                const nv = param.split('=');
                params[decodeURIComponent(nv[0])] = nv.length > 0 ? decodeURIComponent(nv[1]) : '';
            });
        }
        return params;
    }
}

class History {

    constructor() {
        window.onpopstate = function (event) {
            const state = /^(\/.+?\.html)?(\/[^?]*)(\?(.*))?$/.exec(event.state);
            if (state) {
                $(document).trigger('path:select', [state[2]]);
                if (state[4]) {
                    $(document).trigger('query:change', [URL.parameters(state[4])]);
                }
            }
        };
    }

    pushUri(uri) {
        if (history.pushState) {
            const current = new URL(window.location.href);
            const next = new URL(uri);
            if (next.path !== current.path) {
                const state = next.path + (current.query ? ('?' + current.query) : '');
                history.pushState(state, next.name, state);
            }
        }
    }

    pushQuery(parameters) {
        if (history.pushState) {
            const current = new URL(window.location.href);
            const query = Object.getOwnPropertyNames(parameters)
                .map(n => encodeURIComponent(n) + '=' + encodeURIComponent(parameters[n])).join('&');
            if (current.query !== query) {
                const state = current.path + (query ? ('?' + query) : '');
                history.pushState(state, current.name, state);
            }
        }
    }
}

(window.CPM = window.CPM || {}).history = new History();

class Widgets {

    constructor() {
        this.registry = {};
    }

    register(widgetClass, selector) {
        if (widgetClass) {
            this.registry[selector || widgetClass.selector] = widgetClass;
        }
    }

    initialize(element) {
        const $element = $(element || document);
        const registry = this.registry;
        Object.getOwnPropertyNames(registry).forEach(function (selector) {
            $element.find(selector).each(function () {
                if (!this.view || !this.view[registry[selector]]) {
                    if (!this.view) {
                        this.view = {};
                    }
                    this.view[registry[selector].name] = new registry[selector](this);
                }
            });
        });
    }

    static getView(element, widgetClass) {
        const el = element ? $(element)[0] || {} : {};
        return el.view ? (widgetClass ? el.view[widgetClass.name] : Widgets.getFirstView(element)) : undefined;
    }

    static getFirstView(element) {
        const el = element ? $(element)[0] || {} : {};
        if (el.view) {
            const keys = Object.getOwnPropertyNames(el.view);
            return keys.length > 0 ? el.view[keys[0]] : undefined;
        }
        return undefined;
    }
}

(window.CPM = window.CPM || {}).widgets = new Widgets();

class ViewWidget {

    constructor(element) {
        this.$el = $(element);
        this.el = this.$el[0];
        this.$ = function (selector) {
            return this.$el.find(selector);
        }.bind(this);
        this.attachLinkHandler();
    }

    attachLinkHandler(event, element) {
        const toolsUri = $('body').data('tools-uri');
        if (toolsUri) { // the re-adjustment of probably mapped tools links...
            const toolsLinkPattern = new RegExp(`^.+(${toolsUri}\\..*)$`);
            $('a').each(function () {
                const $link = $(this);
                const href = $link.attr('href');
                if (href && '#' !== href) {
                    const matcher = toolsLinkPattern.exec(href);
                    if (matcher) {
                        $link.attr('href', matcher[1]);
                    }
                }
            });
        }
        $(element || this.el).find('a[data-href]').click(function (event) {
            event.preventDefault();
            const $link = $(event.currentTarget);
            window.open($link.data('href'), $link.data('target') || '_self');
            return false;
        });
    }

    onContentLoaded(event, element) {
        CPM.widgets.initialize(element || this.el);
        this.attachLinkHandler(event, element);
    }

    loadContent($element, url, cbSuccess, cbError) {
        $.ajax({
            type: 'GET',
            url: url,
            success: function (content) {
                $element.html(content);
                if (cbSuccess) {
                    cbSuccess($element);
                } else {
                    this.onContentLoaded(undefined, $element);
                }
            }.bind(this),
            error: function () {
                if (cbError) {
                    cbError();
                }
            }.bind(this),
            async: true,
            cache: false
        });
    }

    formData(form) {
        const formData = new FormData($(form)[0]);
        if (!formData.get('_charset_')) {
            formData.set('_charset_', 'UTF-8');
        }
        return formData;
    }

    formGetUrl(form) {
        return ($(form).data("action") || $(form).attr("action")) + '?' + ([...this.formData(form).entries()]
            .map(x => encodeURIComponent(x[0]) + '=' + encodeURIComponent(x[1])).join('&'));
    }

    sanitizeHtml(string) {
        return this.sanitize(string, {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': "'"
        });
    }

    sanitizeAttr(string) {
        return this.sanitize(string, {
            '"': "'"
        });
    }

    sanitize(string, map) {
        const reg = new RegExp('[' + Object.keys(map).join('') + ']', 'ig');
        return string.replace(reg, (match) => (map[match]));
    }
}

class ResumingTabs extends ViewWidget {

    static selector = '.resuming-tabs';

    static css = {
        nav: ResumingTabs.selector + '_nav',
        link: ResumingTabs.selector + '_nav .nav-link',
        pane: ResumingTabs.selector + '_pane'
    };

    constructor(element, identifier, onShownCallback) {
        super(element)
        this.onShownCallback = onShownCallback;
        this.profile = new Profile(identifier || this.$el.data('tabs-id'));
        this.$(ResumingTabs.css.nav + ' a[data-bs-toggle="tab"]').on('shown.bs.tab', this.onTabShown.bind(this));
        this.showTab(this.profile.get('currentTab'), true);
    }

    activeTabId() {
        const active = this.$(ResumingTabs.css.link + '.active').attr('aria-controls');
        return active || this.$(ResumingTabs.css.link).first().attr('aria-controls')
    }

    $tabPane(tabId) {
        return tabId ? this.$(ResumingTabs.css.pane + '[id="' + tabId + '"]') : undefined;
    }

    onTabShown(event) {
        const tabId = $(event.target).attr('aria-controls');
        this.profile.set('currentTab', tabId);
        if (this.onShownCallback) {
            this.onShownCallback(event, tabId, this.$tabPane(tabId));
        }
    }

    showTab(tabId, force) {
        const $tab = this.$(ResumingTabs.css.link + '[aria-controls="' + tabId + '"]');
        const $target = $tab.length > 0 ? $tab : (force ? this.$(ResumingTabs.css.link).first() : undefined);
        if ($target && $target.length > 0) {
            bootstrap.Tab.getOrCreateInstance($target[0]).show();
        }
    }
}

CPM.widgets.register(ResumingTabs);

/**
 * A full-viewport, semi-transparent, click-consuming curtain with a centered spinner, shown
 * while a long-running request (dialog open or submit) is in flight, so a second click can't be
 * fired accidentally - shared by 'Dialog'/'DialogForm' below via 'CPM.curtain.show()'/'.hide()';
 * the curtain element is created once and reused across calls.
 */
class Curtain {

  show() {
    if (!this.$el) {
      this.$el = $('<div class="tools-action_curtain">'
        + '<div class="spinner-border" role="status"><span class="visually-hidden">Loading...</span></div>'
        + '</div>');
      $('body').append(this.$el);
    }
    this.$el.addClass('shown');
  }

  hide() {
    if (this.$el) {
      this.$el.removeClass('shown');
    }
  }
}

(window.CPM = window.CPM || {}).curtain = new Curtain();

/**
 * Shows the outcome of a completed action as a dismissible, self-closing Bootstrap alert - used
 * for a 'DialogForm' submit whose response carries a 'log' (e.g. install/uninstall/assemble's
 * line-per-change operation log, see 'JcrPackageOperations.OperationLog'), rendered as a
 * scrollable list beneath the summary message. Any still-visible alert from a previous action is
 * dismissed first, so a stale result never lingers into the next one.
 */
CPM.showActionResult = function (message, lines, success) {
  $('.tools-action_result').each((i, el) => bootstrap.Alert.getOrCreateInstance(el).close());
  const $alert = $('<div class="tools-action_result alert alert-dismissible fade show" role="alert"></div>')
    .addClass(success ? 'alert-success' : 'alert-danger')
    .append($('<div class="message"></div>').text(message))
    .append('<button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>');
  if (lines && lines.length > 0) {
    const $list = $('<ul class="tools-action_result-lines"></ul>');
    lines.forEach((line) => $list.append($('<li></li>').text(line)));
    $alert.append($list);
  }
  $('body').append($alert);
  window.setTimeout(() => {
    const instance = bootstrap.Alert.getInstance($alert[0]);
    if (instance) {
      instance.close();
    }
  }, 8000);
};

/**
 * An on-demand loaded Bootstrap modal dialog: 'open()' fetches the dialog's HTML fragment from
 * 'url', appends it to <body> and shows it; whenever the modal is hidden again - on cancel, on
 * backdrop/ESC dismissal, or programmatically after a successful submit (see 'DialogForm' below)
 * - its markup is removed from the DOM again, so no dialog is ever left lingering in the page.
 * Shows 'CPM.curtain' while the fragment is being fetched.
 */
class Dialog {

  constructor(url) {
    this.url = url;
  }

  open(onReady) {
    CPM.curtain.show();
    $.ajax({
      type: 'GET',
      url: this.url,
      success: function (html) {
        this.$el = $(html).appendTo('body');
        this.modal = new bootstrap.Modal(this.$el[0]);
        // Bootstrap sets aria-hidden="true" on the modal root as the hide transition starts;
        // if the element that triggered the close (e.g. the '.btn-close' button, or any field
        // still focused when Cancel/Save is pressed) is still focused at that point, the
        // browser logs an accessibility warning - blur it first so focus has already left the
        // modal before aria-hidden is applied
        this.$el.on('hide.bs.modal', function () {
          const active = document.activeElement;
          if (active && this.$el[0].contains(active)) {
            active.blur();
          }
        }.bind(this));
        this.$el.on('hidden.bs.modal', this.destroy.bind(this));
        CPM.widgets.initialize(this.$el);
        if (onReady) {
          onReady(this.$el, this);
        }
        this.modal.show();
      }.bind(this),
      complete: () => CPM.curtain.hide(),
      async: true,
      cache: false
    });
    return this;
  }

  close() {
    if (this.modal) {
      this.modal.hide();
    }
  }

  destroy() {
    if (this.$el) {
      this.$el.remove();
    }
    this.$el = undefined;
    this.modal = undefined;
  }
}

CPM.Dialog = Dialog;

/**
 * Generic AJAX submit handling for a form inside a 'Dialog' fragment (a '<form class="tools-dialog_form">'
 * anywhere under the dialog's root element): submits as multipart form data, closes the enclosing
 * modal on success (which triggers its removal from the DOM, see 'Dialog' above) and fires a
 * 'dialog:success' document event carrying the response so the page can refresh itself, or shows
 * the failure message inline (in a '.tools-dialog_error' element) on error. Shows 'CPM.curtain'
 * while the request is in flight, and - if the response carries a 'log' array - a final
 * {@link CPM.showActionResult} summary of what changed.
 */
class DialogForm extends ViewWidget {

  static selector = '.tools-dialog_form';

  constructor(element) {
    super(element);
    this.$el.on('submit', this.onSubmit.bind(this));
  }

  onSubmit(event) {
    event.preventDefault();
    this.$el.find('.tools-dialog_error').addClass('d-none').text('');
    CPM.curtain.show();
    $.ajax({
      type: this.$el.attr('method') || 'POST',
      url: this.$el.attr('action'),
      data: this.formData(this.$el),
      processData: false,
      contentType: false,
      success: this.onSuccess.bind(this),
      error: this.onError.bind(this),
      complete: () => CPM.curtain.hide(),
      async: true,
      cache: false
    });
    return false;
  }

  onSuccess(result) {
    // captured before hide()/destroy() removes the dialog from the DOM - so the result alert
    // (which can still be sitting there a while later) shows which action it belongs to
    const title = this.$el.closest('.modal').find('.modal-title').text().trim();
    $(document).trigger('dialog:success', [this.el, result]);
    const modalEl = this.$el.closest('.modal')[0];
    if (modalEl) {
      bootstrap.Modal.getOrCreateInstance(modalEl).hide();
    }
    if (result && Array.isArray(result.log)) {
      const message = result.error ? 'Completed with errors.' : 'Completed successfully.';
      CPM.showActionResult(title ? title + ': ' + message : message, result.log, !result.error);
    }
  }

  onError(jqXHR) {
    const message = (jqXHR.responseJSON && jqXHR.responseJSON.message) || jqXHR.statusText || 'Request failed.';
    this.$el.find('.tools-dialog_error').removeClass('d-none').text(message);
  }
}

CPM.widgets.register(DialogForm);

/**
 * Pre-selects a '<select data-value="...">' from its own 'data-value' attribute - the template
 * engine can only test truthiness (no per-option equality check), so a select whose current value
 * must be pre-selected server-side is rendered with a 'data-value' attribute instead of a
 * per-option 'selected', and this widget applies it once the element is in the DOM.
 */
class SelectValue extends ViewWidget {

  static selector = 'select[data-value]';

  constructor(element) {
    super(element);
    const value = this.$el.data('value');
    if (value !== undefined && value !== '') {
      this.el.value = value;
    }
  }
}

CPM.widgets.register(SelectValue);
