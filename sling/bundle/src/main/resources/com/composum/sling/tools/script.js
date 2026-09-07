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

    // splits a full URL path into the servlet/page prefix up to and including '.html' (group 1,
    // optional) and the repository resource path after it (group 2) - the same split 'onpopstate'
    // always needed (its stored state is the full URL path, but only the resource path is
    // meaningful to the rest of the app), now also reused by 'pushUri' so the page title shows
    // just the resource path there too, not the full URL (which includes the servlet base, e.g.
    // '/apps/cpm/...')
    static PATH_PATTERN = /^(\/.+?\.html)?(\/[^?]*)(\?(.*))?$/;

    constructor() {
        // the page's own, server-rendered title (e.g. "Composum Browser") - kept as the constant
        // prefix every path-specific title below is built from, rather than accumulating suffixes
        // on repeated navigation
        this.baseTitle = document.title;
        window.onpopstate = (event) => {
            const state = History.PATH_PATTERN.exec(event.state);
            if (state) {
                this.updateTitle(state[2]);
                $(document).trigger('path:select', [state[2]]);
                if (state[4]) {
                    $(document).trigger('query:change', [URL.parameters(state[4])]);
                }
            }
        };
    }

    // most browsers ignore 'pushState's own 'title' argument entirely for what they show in their
    // history list/back-forward UI - what actually ends up there is 'document.title' as it stood
    // at the moment the state was pushed, so that has to be set explicitly here (this also updates
    // the browser tab's own title as a side effect, which is a welcome bonus, not just history)
    updateTitle(path) {
        document.title = path && path !== '/' ? `${this.baseTitle} - ${path}` : this.baseTitle;
    }

    pushUri(uri) {
        if (history.pushState) {
            const current = new URL(window.location.href);
            const next = new URL(uri);
            if (next.path !== current.path) {
                const state = next.path + (current.query ? ('?' + current.query) : '');
                const match = History.PATH_PATTERN.exec(state);
                this.updateTitle(match ? match[2] : next.path);
                history.pushState(state, document.title, state);
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
        this.$(ResumingTabs.css.nav + ' a[data-toggle="tab"]').on('shown.bs.tab', this.onTabShown.bind(this));
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
        if ($tab.length > 0) {
            $tab.tab('show');
        } else if (force) {
            this.$(ResumingTabs.css.link).first().tab('show');
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

/**
 * Shared jsTree data-fetching for any tree bound to the generic {@code TreeNode} JSON endpoint
 * (server-side: {@code com.composum.sling.tools.TreeNode}/{@code AbstractToolsPlugin#treeResult})
 * - both the Browser page's own tree ('BrowserTree' in browser/script.js) and this file's
 * 'TreePicker' use it, so "fetch a node from its JSON endpoint, then assign every returned node a
 * jsTree-safe id (their repository paths as-is are not, e.g. they contain slashes)" lives in
 * exactly one place instead of two near-identical copies. 'prefix' just keeps two independent tree
 * instances' generated ids easier to tell apart when inspecting the DOM - since each is always its
 * own separate DOM subtree, a collision was never actually possible either way.
 */
(window.CPM = window.CPM || {}).tree = {
  nodeId(prefix, path) {
    if (path && (typeof path !== 'string' || path.indexOf(prefix) !== 0)) {
      if (Array.isArray(path)) {
        path = path.join('/');
      }
      path = (prefix + btoa(encodeURIComponent(path))).replace(/=/g, '-').replace(/\//g, '_');
    }
    return path;
  },
  fetchNode(prefix, url, callback) {
    $.ajax({
      type: 'GET',
      url: url,
      success: (result) => {
        result.id = CPM.tree.nodeId(prefix, result.path);
        (result.children || []).forEach((child) => {
          child.id = CPM.tree.nodeId(prefix, child.path);
        });
        callback(result);
      },
      async: true,
      cache: false
    });
  }
};

/**
 * A generic repository-path autocomplete for any '<input>'/'<textarea>' carrying the
 * 'tools-path_input' class and a 'data-suggest-uri' (a GET endpoint accepting a 'path' query
 * parameter and returning a JSON array of suggested paths - see
 * {@code AbstractToolsPlugin#pathSuggestions} for the shared server-side lookup any plugin can
 * route to). Debounces input, shows a dropdown of matching child paths below the field, and
 * replaces the field's whole value on selection - deliberately whole-value replacement rather than
 * cursor-position-aware partial completion, since every current use (a destination/search-root
 * path field, or a property value that itself IS a path) treats the field as a single path, not
 * text a path is embedded in. Works on any field carrying the marker class, regardless of which
 * plugin's dialog rendered it, and does nothing at all if 'data-suggest-uri' is absent.
 * <p>
 * Appended to '<body>' and positioned 'fixed' from the field's own bounding rect (recomputed
 * whenever the list is shown, and continuously while it stays open - see 'trackPosition'/
 * 'untrackPosition') rather than 'position: absolute' anchored to the field's own parent: a field
 * can sit inside an 'overflow: auto' ancestor (e.g. the Properties dialog's own scrolling value
 * list, 'changes/dialogs/property.html'), which would otherwise clip the dropdown the moment it
 * extends past that ancestor's own bounds - a fixed-position dropdown anchored to the viewport
 * has no such ancestor to be clipped by.
 * <p>
 * Sets 'autocomplete="off"' on the field itself - without it, the browser's own remembered-value
 * dropdown for that field competes for the same screen space (visually overlapping this widget's
 * list) and, worse, captures Up/Down/Enter itself while it is showing, leaving no way to reach
 * this widget's own suggestions with the keyboard at all.
 */
class PathPicker extends ViewWidget {

  static selector = '.tools-path_input';

  constructor(element) {
    super(element);
    this.suggestUri = this.$el.data('suggest-uri');
    if (!this.suggestUri) {
      return;
    }
    this.el.setAttribute('autocomplete', 'off');
    this.$list = $('<ul class="tools-path_suggestions d-none"></ul>').appendTo('body');
    this.active = -1;
    this.onReposition = this.updatePosition.bind(this);
    this.$el.on('input', this.onInput.bind(this));
    this.$el.on('keydown', this.onKeyDown.bind(this));
    this.$el.on('blur', () => window.setTimeout(this.hide.bind(this), 150));
    this.$list.on('mousedown', 'li', (event) => this.select($(event.currentTarget).text()));
  }

  // 'scroll' does not bubble, but - unlike most other events - it IS still dispatched during the
  // capture phase to ancestor listeners, which is what makes one 'document'-level, capture:true
  // listener enough to catch scrolling from *any* scrollable ancestor (not just the window itself)
  trackPosition() {
    document.addEventListener('scroll', this.onReposition, true);
    window.addEventListener('resize', this.onReposition);
  }

  untrackPosition() {
    document.removeEventListener('scroll', this.onReposition, true);
    window.removeEventListener('resize', this.onReposition);
  }

  updatePosition() {
    const rect = this.el.getBoundingClientRect();
    this.$list.css({
      top: rect.bottom + 'px',
      left: rect.left + 'px',
      width: rect.width + 'px'
    });
  }

  onInput() {
    window.clearTimeout(this.timer);
    this.timer = window.setTimeout(this.fetchSuggestions.bind(this), 200);
  }

  // Up/Down move the active suggestion, Enter accepts it (only while one is actually active, so a
  // plain Enter with the list merely open still falls through to its normal behavior - submitting
  // the form for a single-line input, a newline for a textarea), Escape closes the list
  onKeyDown(event) {
    if (this.$list.hasClass('d-none')) {
      return;
    }
    const $items = this.$list.find('li');
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.setActive(Math.min(this.active + 1, $items.length - 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.setActive(Math.max(this.active - 1, 0));
        break;
      case 'Enter':
        if (this.active >= 0) {
          event.preventDefault();
          this.select($items.eq(this.active).text());
        }
        break;
      case 'Escape':
        this.hide();
        break;
    }
  }

  setActive(index) {
    const $items = this.$list.find('li').removeClass('active');
    this.active = index;
    if (index >= 0) {
      const item = $items.eq(index).addClass('active')[0];
      if (item && item.scrollIntoView) {
        item.scrollIntoView({block: 'nearest'});
      }
    }
  }

  fetchSuggestions() {
    // read fresh rather than from the constructor-time 'this.suggestUri' - a consumer (e.g. the
    // Change-Property dialog's jcr:primaryType/jcr:mixinTypes handling, see changes/script.js's
    // 'PropertyValues#updateNameMode') can repoint 'data-suggest-uri' after construction to switch
    // what a field autocompletes against without having to rebuild the widget itself
    const suggestUri = this.$el.data('suggest-uri');
    if (!suggestUri) {
      return;
    }
    $.ajax({
      type: 'GET',
      url: suggestUri,
      data: {path: this.el.value},
      success: (result) => this.showSuggestions(Array.isArray(result) ? result : []),
      async: true,
      cache: false
    });
  }

  showSuggestions(paths) {
    this.$list.empty();
    this.active = -1;
    if (paths.length === 0) {
      this.hide();
      return;
    }
    paths.forEach((path) => this.$list.append($('<li></li>').text(path)));
    this.updatePosition();
    this.$list.removeClass('d-none');
    this.trackPosition();
  }

  hide() {
    this.$list.addClass('d-none').empty();
    this.active = -1;
    this.untrackPosition();
  }

  select(path) {
    this.el.value = path;
    this.hide();
    this.el.focus();
  }
}

CPM.widgets.register(PathPicker);

/**
 * A "Browse..." button - rendered server-side as a trailing button in the same '.input-group' as
 * the field it belongs to (see changes/dialogs/move.html and .../propertyValue.html) - that opens
 * a small jsTree popup for picking a repository path: a fuller-featured complement to
 * {@link PathPicker}'s autocomplete-while-typing, for a path that's easier to browse to than to
 * type from memory. Speaks the same generic "tree" JSON protocol the Browser page's own tree
 * already uses (server-side: {@code com.composum.sling.tools.TreeNode}/
 * {@code AbstractToolsPlugin#treeResult}), and therefore relies on jsTree already being loaded by
 * the surrounding page rather than loading it itself - true for every current use, since Changes'
 * dialogs only ever render inside the Browser page, which already loads jsTree for its own tree.
 * One shared modal/jsTree instance is lazily created and reused across every picker button on the
 * page; only the target field and the button's own 'data-tree-uri' change per invocation.
 */
class TreePicker extends ViewWidget {

  static selector = '.tools-tree_picker-btn';

  constructor(element) {
    super(element);
    this.treeUri = this.$el.data('tree-uri');
    this.$field = this.$el.closest('.input-group').find('.tools-path_input');
    this.$el.on('click', () => this.open());
  }

  open() {
    if (!this.treeUri) {
      return;
    }
    const modal = TreePicker.modal();
    modal.data('picker', this);
    modal.find('.tools-tree_picker-choose').prop('disabled', true);
    TreePicker.jstree.settings.core.data = this.nodeData.bind(this);
    // if the field already holds something path-shaped, drill down to and pre-select it once the
    // tree has (re-)loaded its root, so "Choose" defaults to the current value instead of nothing
    const currentPath = this.$field.val();
    TreePicker.$tree.one('refresh.jstree', () => {
      if (currentPath && currentPath.startsWith('/')) {
        TreePicker.openPath(currentPath);
      }
    });
    TreePicker.jstree.refresh(true);
    bootstrap.Modal.getOrCreateInstance(modal[0]).show();
  }

  choose() {
    const node = TreePicker.selectedNode();
    if (node) {
      this.$field.val(node.original.path);
    }
  }

  nodeData(node, callback) {
    const path = node.id === '#' ? '/' : node.original.path;
    CPM.tree.fetchNode(TreePicker.ID_PREFIX, this.treeUri + path,
      (result) => callback.call(TreePicker.$tree, result));
  }

  static ID_PREFIX = 'CTP_';

  static nodeId(path) {
    return CPM.tree.nodeId(TreePicker.ID_PREFIX, path);
  }

  static selectedNode() {
    const ids = TreePicker.jstree.get_selected();
    return ids.length > 0 ? TreePicker.jstree.get_node(ids[0]) : undefined;
  }

  // drills down to 'path' one segment at a time, lazily opening each ancestor node in turn (jsTree
  // only invokes the 'open_node' callback once that node's own children have actually loaded), then
  // selects and scrolls to the final node - the same pattern the Browser page's own tree uses for
  // this (see 'BrowserTree#openNode' in browser/script.js). Stops silently (nothing selected beyond
  // whatever was already reached) if a segment can't be found, e.g. because the path doesn't
  // actually exist - a best-effort convenience, not a correctness requirement.
  static openPath(path) {
    TreePicker.jstree.deselect_all();
    const names = path.split('/');
    let index = 1;
    const drilldown = (current) => {
      const $node = TreePicker.$tree.find('#' + TreePicker.nodeId(current));
      if ($node.length === 0) {
        return;
      }
      TreePicker.jstree.open_node($node, () => {
        if (index < names.length && names[index]) {
          drilldown(current + (current === '/' ? '' : '/') + names[index++]);
        } else {
          TreePicker.jstree.select_node($node);
          // same deliberate delay 'BrowserTree#openNode' uses for this exact final step - jsTree's
          // own DOM update for the just-opened node, and the modal's own Bootstrap fade-in
          // transition, both still have layout in flux right when 'open_node's callback fires;
          // scrolling immediately computes a position against that not-yet-settled geometry
          window.setTimeout(() => {
            const liveNode = TreePicker.$tree.find('#' + TreePicker.nodeId(current))[0];
            if (liveNode && liveNode.scrollIntoView) {
              liveNode.scrollIntoView({block: 'center'});
            }
          }, 200);
        }
      });
    };
    drilldown('/');
  }

  // lazily builds the one shared modal + jsTree instance on first use - a picker button just
  // repoints 'core.data' at its own field/tree-uri and forces a refresh before showing it
  static modal() {
    if (!TreePicker.$modal) {
      TreePicker.$modal = $(
        '<div class="modal fade tools-tree_picker-modal" tabindex="-1"><div class="modal-dialog">'
        + '<div class="modal-content"><div class="modal-header">'
        + '<h5 class="modal-title">Select Path</h5>'
        + '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>'
        + '</div><div class="modal-body"><div class="tools-tree_picker-tree"></div></div>'
        + '<div class="modal-footer">'
        + '<button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>'
        + '<button type="button" class="btn btn-primary tools-tree_picker-choose" disabled>Choose</button>'
        + '</div></div></div></div>').appendTo('body');
      TreePicker.$tree = TreePicker.$modal.find('.tools-tree_picker-tree');
      TreePicker.$tree.jstree({
        'plugins': ['wholerow'],
        'core': {
          'animation': false,
          'data': () => {
          },
          'cache': false,
          'multiple': false,
          'themes': {'name': 'proton'}
        }
      });
      TreePicker.jstree = TreePicker.$tree.jstree(true);
      TreePicker.$tree.on('select_node.jstree', (event, data) => {
        TreePicker.$modal.find('.tools-tree_picker-choose').prop('disabled', !data.node);
        // selecting a node also expands it one level, matching the Browser page's own tree
        // ('BrowserTree#onNodeSelected') - lets the user drill down with plain clicks alone,
        // without needing to separately hit the small expand arrow every time
        TreePicker.jstree.open_node(data.node);
      });
      TreePicker.$tree.on('dblclick', '.jstree-anchor', () => {
        TreePicker.$modal.find('.tools-tree_picker-choose').trigger('click');
      });
      TreePicker.$modal.find('.tools-tree_picker-choose').on('click', () => {
        const picker = TreePicker.$modal.data('picker');
        if (picker) {
          picker.choose();
        }
        bootstrap.Modal.getOrCreateInstance(TreePicker.$modal[0]).hide();
      });
      // this modal is always opened from *inside* an already-open dialog (Move/Change Property) -
      // Bootstrap does not itself raise a nested modal (or its own backdrop) above the one it is
      // stacked on, so both would otherwise render at the same default z-index and fall back to
      // DOM order, which is not reliably "on top". The modal's own elevated z-index is fixed in CSS
      // (.tools-tree_picker-modal); only its backdrop - a generic, class-only '.modal-backdrop' div
      // Bootstrap (re-)creates fresh on every show, indistinguishable from the dialog-below's own
      // one except by being the most recently added - needs bumping here, once it actually exists.
      TreePicker.$modal.on('shown.bs.modal', () => {
        $('.modal-backdrop').last().addClass('tools-tree_picker-backdrop');
      });
      // same fix as 'Dialog#open' above: Bootstrap sets aria-hidden="true" on the modal root as
      // the hide transition starts, which the browser flags if the element that triggered the
      // close (Cancel/Choose, or a field that still had focus) is still focused at that point -
      // this modal is built by hand rather than through 'Dialog', so it needs its own copy
      TreePicker.$modal.on('hide.bs.modal', () => {
        const active = document.activeElement;
        if (active && TreePicker.$modal[0].contains(active)) {
          active.blur();
        }
      });
    }
    return TreePicker.$modal;
  }
}

CPM.widgets.register(TreePicker);
