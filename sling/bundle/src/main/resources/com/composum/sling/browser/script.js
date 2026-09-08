class BrowserPathRelated extends ViewWidget {

  static selector = '.tools-navbar .browser_path_related';

  constructor(element) {
    super(element);
    this.profile = new Profile('browser');
    $(document).on('path:selected', this.onPathSelected.bind(this));
  }

  onPathSelected(event, path) {
    this.path = path;
    const url = this.$el.data('uri');
    if (url) {
      this.loadContent(this.$el, `${url}${path}`, ($element) => {
        this.dropdown = new bootstrap.Dropdown(this.$el.find('.dropdown-menu')[0]);
        this.$el.find('.browser_path_related-menu a').on('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          this.dropdown.hide();
          const $action = $(event.currentTarget);
          $(document).trigger('path:select', [$action.data('path')]);
          return false;
        });
      });
    }
  }
}

CPM.widgets.register(BrowserPathRelated);

class BrowserActions extends ViewWidget {

  static selector = '.tools-navbar .browser_actions';

  constructor(element) {
    super(element);
    this.profile = new Profile('browser');
    $(document).on('path:selected', this.onPathSelected.bind(this));
  }

  onPathSelected(event, path) {
    this.path = path;
    const url = this.$el.data('uri');
    if (url) {
      this.loadContent(this.$el, `${url}${path}`, ($element) => {
        this.$currentAction = this.$el.find('.current-action');
        this.dropdown = new bootstrap.Dropdown(this.$el.find('.dropdown-menu')[0]);
        this.lastAction()
        this.$el.find('.dropdown-item').on('click', (event) => {
          const $action = $(event.currentTarget);
          this.profile.set('lastAction', $action.data('key'));
          this.lastAction();
        })
        this.$el.find('.browser-action[data-method]').on('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          this.dropdown.hide();
          const $action = $(event.currentTarget);
          const method = $action.data('method');
          const url = $action.attr('href');
          const key = $action.data('key');
          this.dismissActionResult();
          this.showCurtain();
          $.ajax({
            type: method,
            url: url,
            success: (result) => {
              this.hideCurtain();
              this.showActionResult(key, result, true);
            },
            error: (jqXHR) => {
              this.hideCurtain();
              this.showActionResult(key, jqXHR.responseJSON, false);
            },
            async: true,
            cache: false
          });
          return false;
        });
      });
    }
  }

  lastAction() {
    const key = this.profile.get('lastAction');
    let $action = key ? this.$el.find(`.dropdown-menu .action-${key} a`) : [];
    if ($action.length === 0) {
      $action = this.$el.find('.dropdown-menu li.action-item a');
      if ($action.length > 1) {
        $action = $($action[0]);
      }
    }
    if ($action.length > 0) {
      this.$currentAction.attr('data-key', $action.data('key'));
      this.$currentAction.attr('href', $action.attr('href'));
      const method = $action.data('method');
      if (method) {
        this.$currentAction.attr('data-method', method);
      } else {
        this.$currentAction.removeAttr('data-method');
      }
      this.$currentAction.attr('title', $action.attr('title'));
      this.$currentAction.attr('target', $action.attr('target'));
      this.$currentAction.html($action.html());
    } else {
      this.$currentAction.attr('href', '');
      this.$currentAction.removeAttr('data-key');
      this.$currentAction.removeAttr('data-method');
      this.$currentAction.removeAttr('title');
      this.$currentAction.removeAttr('target');
      this.$currentAction.html('--');
    }
  }

  // dismisses a still-visible result alert from a previous action; called right when a new
  // action starts, so a stale result never lingers into the next one
  dismissActionResult() {
    $('.browser-action_result').each((i, el) => bootstrap.Alert.getOrCreateInstance(el).close());
  }

  // shows a full-viewport, semi-transparent, click-consuming curtain with a spinner while a
  // long-running action (e.g. a deep tree activation) is in flight, so a second click can't be
  // fired accidentally; the curtain element is created once and reused across calls
  showCurtain() {
    if (!this.$curtain) {
      this.$curtain = $('<div class="browser-action_curtain">'
        + '<div class="spinner-border" role="status"><span class="visually-hidden">Loading...</span></div>'
        + '</div>');
      $('body').append(this.$curtain);
    }
    this.$curtain.addClass('shown');
  }

  hideCurtain() {
    if (this.$curtain) {
      this.$curtain.removeClass('shown');
    }
  }

  // shows the outcome of an action (e.g. activate/deactivate) as a dismissible, self-closing
  // Bootstrap alert; 'result' is the action's JSON response body ({targets, error}), if any -
  // the returned target paths are rendered as a scrollable list (see .browser-action_result* in style.css)
  showActionResult(key, result, success) {
    const targets = (result && result.targets) || [];
    const error = result && result.error;
    const ok = success && !error;
    const verb = key === 'activate' ? 'Activated' : key === 'deactivate' ? 'Deactivated' : 'Action applied to';
    const summary = ok ? `${verb} ${targets.length} resource(s).` : (error || 'The action failed.');
    const $alert = $('<div class="browser-action_result alert alert-dismissible fade show" role="alert"></div>')
      .addClass(ok ? 'alert-success' : 'alert-danger')
      .append($('<div class="message"></div>').text(summary))
      .append('<button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>');
    if (targets.length > 0) {
      const $list = $('<ul class="browser-action_result-targets"></ul>');
      targets.forEach((path) => $list.append($('<li></li>').text(path)));
      $alert.append($list);
    }
    $('body').append($alert);
    window.setTimeout(() => {
      const instance = bootstrap.Alert.getInstance($alert[0]);
      if (instance) {
        instance.close();
      }
    }, 8000);
  }
}

CPM.widgets.register(BrowserActions);

class ToolLink extends ViewWidget {

  static selector = '.navbar-nav .tool-link';

  constructor(element) {
    super(element);
    this.$el.click(function (event) {
      event.preventDefault();
      $(document).trigger('tool:toggle', [this.$el.data('tool-name')]);
      return false;
    }.bind(this));
    $(document).on('path:selected', this.onPathSelected.bind(this));
  }

  onPathSelected(event, path) {
    const uri = this.$el.data('tool-uri');
    this.$el.data('tool-uri', uri.replace(/\.html(\/.*)?$/, `.html${path}`));
  }

  static setActive(toolName) {
    $(ToolLink.selector).removeClass('active');
    if (toolName) {
      $(ToolLink.selector + '[data-tool-name="' + toolName + '"]').addClass('active');
    }
  }
}

CPM.widgets.register(ToolLink);

class BrowserPathField extends ViewWidget {

  static selector = '.browser_path-field';

  constructor(element) {
    super(element);
    this.$el.on('change', this.onPathChanged.bind(this));
    $(document).on('path:selected', this.onPathSelected.bind(this));
  }

  onPathChanged(event) {
    window.setTimeout(function () {
      $(document).trigger('path:select', [this.$el.val()]);
    }.bind(this), 300);
  }

  onPathSelected(event, path) {
    this.$el.val(path);
    const $browserLink = $('.tools-navbar .tools-page-link_browser');
    if ($browserLink.length > 0) {
      const href = $browserLink.attr('href');
      $browserLink.attr('href', href.replace(/\.html(\/.*)?$/, `.html${path}`));
    }
  }
}

CPM.widgets.register(BrowserPathField);

class BrowserTree extends ViewWidget {

  static selector = '.browser-page_browser_tree';

  constructor(element) {
    super(element)
    const treeOptions = {
      'plugins': [
        'types',
        'unique',
        'wholerow'
      ],
      'core': {
        'animation': false,
        'data': this.nodeData.bind(this),
        'cache': false,
        'load_open': true,
        'multiple': false,
        'force_text': true,
        'themes': {
          'name': 'proton'
        }
      },
      'types': {
        'default': {'icon': 'bi bi-box'},
        'synthetic': {'icon': 'bi bi-circle'},
        'summary': {'icon': 'bi bi-hand-thumb-up'},
        'root': {'icon': 'bi bi-diagram-3'},
        'system': {'icon': 'bi bi-gear-wide-connected'},
        'activities': {'icon': 'bi bi-activity'},
        'nodetypes': {'icon': 'bi bi-tags'},
        'nodetype': {'icon': 'bi bi-tag'},
        'versionstorage': {'icon': 'bi bi-clock-history'},
        'folder': {'icon': 'bi bi-folder'},
        'resource-folder': {'icon': 'bi bi-folder'},
        'orderedfolder': {'icon': 'bi bi-folder-fill'},
        'registry': {'icon': 'bi bi-database'},
        'package': {'icon': 'bi bi-file-earmark-zip'},
        'resource-package': {'icon': 'bi bi-file-earmark-zip'},
        'tenant': {'icon': 'bi bi-bank'},
        'component': {'icon': 'bi bi-puzzle'},
        'container': {'icon': 'bi bi-boxes'},
        'element': {'icon': 'bi bi-box'},
        'site': {'icon': 'bi bi-diagram-3'},
        'siteconfiguration': {'icon': 'bi bi-three-dots'},
        'page': {'icon': 'bi bi-globe'},
        'pagecontent': {'icon': 'bi bi-three-dots'},
        'page-designer': {'icon': 'bi bi-box'},
        'resource-designer': {'icon': 'bi bi-file-earmark-code'},
        'resource-redirect': {'icon': 'bi bi-share'},
        'resource-parsys': {'icon': 'bi bi-three-dots-vertical'},
        'resource-console': {'icon': 'bi bi-laptop'},
        'resource-pckgmgr': {'icon': 'bi bi-laptop'},
        'resource-path': {'icon': 'bi bi-bookmark'},
        'resource-resources': {'icon': 'bi bi-funnel'},
        'resource-strings': {'icon': 'bi bi-funnel'},
        'resource-felix': {'icon': 'bi bi-gear'},
        'resource-guide': {'icon': 'bi bi-book'},
        'resource-servlet': {'icon': 'bi bi-gear'},
        'acl': {'icon': 'bi bi-key'},
        'authorizablefolder': {'icon': 'bi bi-diamond'},
        'group': {'icon': 'bi bi-people'},
        'service': {'icon': 'bi bi-gear'},
        'user': {'icon': 'bi bi-person'},
        'linkedfile': {'icon': 'bi bi-link-45deg'},
        'file': {'icon': 'bi bi-file-earmark'},
        'resource': {'icon': 'bi bi-file-earmark'},
        'resource-file': {'icon': 'bi bi-file-earmark'},
        'file-image': {'icon': 'bi bi-file-earmark-image'},
        'resource-image': {'icon': 'bi bi-file-earmark-image'},
        'file-video': {'icon': 'bi bi-file-earmark-play'},
        'resource-video': {'icon': 'bi bi-file-earmark-play'},
        'file-text': {'icon': 'bi bi-file-earmark-text'},
        'resource-text': {'icon': 'bi bi-file-earmark-text'},
        'file-text-plain': {'icon': 'bi bi-file-earmark-text'},
        'file-text-x-log': {'icon': 'bi bi-file-earmark-text'},
        'resource-text-plain': {'icon': 'bi bi-file-earmark-code'},
        'file-text-html': {'icon': 'bi bi-globe'},
        'resource-text-html': {'icon': 'bi bi-file-earmark-code'},
        'file-text-css': {'icon': 'bi bi-file-earmark-code'},
        'resource-text-css': {'icon': 'bi bi-file-earmark-code'},
        'file-javascript': {'icon': 'bi bi-file-earmark-code'},
        'resource-javascript': {'icon': 'bi bi-file-earmark-code'},
        'file-text-javascript': {'icon': 'bi bi-file-earmark-code'},
        'resource-text-javascript': {'icon': 'bi bi-file-earmark-code'},
        'file-text-x-java-properties': {'icon': 'bi bi-file-earmark-code'},
        'file-text-x-java-source': {'icon': 'bi bi-file-earmark-code'},
        'resource-text-x-java-source': {'icon': 'bi bi-file-earmark-code'},
        'file-octet-stream': {'icon': 'bi bi-file-earmark-code'},
        'resource-octet-stream': {'icon': 'bi bi-file-earmark-code'},
        'file-pdf': {'icon': 'bi bi-file-earmark-pdf'},
        'resource-pdf': {'icon': 'bi bi-file-earmark-pdf'},
        'file-zip': {'icon': 'bi bi-file-earmark-zip'},
        'resource-zip': {'icon': 'bi bi-file-earmark-zip'},
        'file-java-archive': {'icon': 'bi bi-file-earmark-zip'},
        'resource-java-archive': {'icon': 'bi bi-file-earmark-zip'},
        'asset': {'icon': 'bi bi-image'},
        'assetcontent': {'icon': 'bi bi-image'},
        'file-binary': {'icon': 'bi bi-file-earmark'},
        'resource-binary': {'icon': 'bi bi-file-earmark'},
        'resource-syntheticresourceproviderresource': {'icon': 'bi bi-code'},
        'clientlibraryfolder': {'icon': 'bi bi-folder-symlink'}
      }
    };
    this.$jstree = this.$el.jstree(treeOptions);
    this.jstree = this.$el.jstree(true);
    this.$jstree
      .on('select_node.jstree', this.onNodeSelected.bind(this));
    $(document)
      .on('page:changed', this.onPageChanged.bind(this))
      .on('path:select', this.doSelectPath.bind(this))
      .on('dialog:success', (event, el, result) => this.onChangesApplied(result))
      .on('changes:applied', (event, result) => this.onChangesApplied(result));
    const path = this.$el.data('path');
    if (path) {
      setTimeout(function () {
        this.openNode(path, function (path) {
          $(document).trigger('path:selected', [path]);
        }.bind(this), true);
      }.bind(this), 500);
    }
  }

  browserUrl(path) {
    return this.$el.data('page-url') + path;
  }

  doSelectPath(event, path) {
    const selected = this.getSelectedNode();
    if (!selected || path !== selected.original.path) {
      this.openNode(path);
    }
  }

  // after a node-modification action (create/delete/move/copy/property change, or a pending
  // Save All/Revert All) - a plain 'path:select' would not be enough, since jsTree caches a
  // node's children once loaded and does not know its structure just changed; 'jstree.refresh()'
  // forces every currently-open node to re-fetch, then the changed path (or, if none is given -
  // e.g. after a property change or a Revert All - the still-selected one) is re-opened/selected.
  // Reacts to both halves of the Changes service's event contract: 'dialog:success' (Create/
  // Delete/Move/Change Property, via the shared 'DialogForm') and 'changes:applied' (Paste, Save
  // All, Revert All) - both carry a '{path, pending}'-shaped JSON result.
  onChangesApplied(result) {
    const target = (result && result.path) || this.getSelectedPath();
    if (target) {
      this.$jstree.one('refresh.jstree', function () {
        this.openNode(target, function (path) {
          $(document).trigger('path:selected', [path]);
        }.bind(this), true);
      }.bind(this));
    }
    this.jstree.refresh();
  }

  triggerPathSelected(path) {
    $(document).trigger('path:selected', [path]);
    CPM.history.pushUri(this.browserUrl(path));
  }

  onPageChanged(event, url) {
    const tree = this;
    $.ajax({
      type: 'GET',
      url: tree.$el.data('tree-url'),
      data: {
        url: url
      },
      success: function (result, msg, xhr) {
        const selected = tree.getSelectedNode();
        let selectedPath = (selected ? selected.original.path : '')
          .replace(/\/jcr:content$/, '');
        if (selectedPath !== result.path) {
          tree.openNode(result.path);
        }
      },
      async: true,
      cache: false
    });
  }

  onNodeSelected(event, data) {
    const node = this.jstree.get_node(data.node.id);
    if (node) {
      this.jstree.open_node(node, function () {
        if (!this.suppressEvent) {
          this.triggerPathSelected(node.original.path);
        }
      }.bind(this));
    }
  }

  getSelectedPath() {
    const node = this.getSelectedNode();
    return node && node.original && node.original.path ? node.original.path : undefined;
  }

  getSelectedNode() {
    const selectedIds = this.jstree.get_selected();
    if (selectedIds.length > 0) {
      return this.jstree.get_node(selectedIds[0]);
    }
    return undefined;
  }

  // re-fetches just the currently selected node's own children from the server (jsTree's
  // 'refresh_node' re-runs the 'data' callback for that one node) - narrower than 'onChangesApplied'
  // below, which refreshes the whole tree; a manual "Reload" action has no mutation result telling
  // it what changed, so it only ever makes sense to reload the one node the user is looking at
  reloadSelected() {
    const node = this.getSelectedNode();
    if (node) {
      this.jstree.refresh_node(node);
    }
  }

  dataUrl(node) {
    const path = node.original && node.original.path ? node.original.path : '/';
    return this.$el.data('tree-url') + path;
  }

  static ID_PREFIX = 'CBT_';

  // fetch-and-assign-ids itself is shared with TreePicker (sling/tools/script.js's 'CPM.tree') -
  // everything specific to *this* tree (its own URL scheme, drilldown/history/select behavior)
  // stays here
  nodeData(node, callback) {
    CPM.tree.fetchNode(BrowserTree.ID_PREFIX, this.dataUrl(node),
      (result) => callback.call(this.$jstree, result));
  }

  nodeId(id) {
    return CPM.tree.nodeId(BrowserTree.ID_PREFIX, id);
  }

  openNode(path, callback, suppressEvent) {
    this.jstree.deselect_all();
    const names = $.isArray(path) ? path : path.split('/');
    let index = 1;
    const tree = this;
    const drilldown = function (path) {
      const id = tree.nodeId(path);
      const $node = tree.$el.find('#' + id);
      tree.jstree.open_node($node, function (node, wasNotOpened) {
        if (index < names.length) {
          drilldown(path + (path === '/' ? '' : '/') + names[index++]);
        } else {
          tree.suppressEvent = suppressEvent;
          try {
            tree.jstree.select_node($node);
            setTimeout(function () {
              const $liveNode = tree.$el.find('#' + id);
              tree.scrollIntoView($liveNode);
              if (callback) {
                callback(path, $liveNode[0])
              }
            }.bind(this), 200);
          } finally {
            delete tree.suppressEvent;
          }
        }
      });
    };
    drilldown('/');
  }

  scrollIntoView($node) {
    const $panel = this.$jstree.closest('.browser-page_browser_tree-panel ');
    const nodePos = $node.position();
    if (nodePos) {
      const nodeTop = nodePos.top;
      const scrollTop = $panel.scrollTop();
      const scrollHeight = $panel.height();
      if (nodeTop < scrollTop + scrollHeight / 5) {
        $panel.scrollTop(nodeTop - scrollHeight / 4);
      } else if (nodeTop > scrollTop + scrollHeight - scrollHeight / 5) {
        $panel.scrollTop(nodeTop - scrollHeight + scrollHeight / 4);
      }
    }
  }
}

CPM.widgets.register(BrowserTree);

class BrowserPanel extends ViewWidget {

  constructor(element) {
    super(element);
    this.profile = new Profile('browser');
    $(document).on('content:loaded', this.attachLinkHandler.bind(this));
  }

  attachLinkHandler(event, element) {
    super.attachLinkHandler(event, element)
    $(element || this.el).find('a.path').click(function (event) {
      event.preventDefault();
      let path = $(event.currentTarget).data('path');
      if (path) {
        path = path.replaceAll(/\/_jcr_/g, '/jcr:');
        if (/\.browser\.[^/]+\.html\//.test(path)) {
          window.open(path, '_self');
        } else {
          $(document).trigger('path:select', [path]);
        }
      }
      return false;
    }.bind(this));
  }
}

class BrowserTool extends BrowserPanel {

  static selector = '.browser-page_browser_tool';

  constructor(element) {
    super(element);
    this.$parent = this.$el.closest('.browser-page_browser_right-panel');
    this.showTool(this.profile.get('currentTool'));
    $(document).on('tool:toggle', function (event, toolName) {
      this.toggleTool(toolName);
    }.bind(this));
  }

  setCurrentTool(toolName) {
    this.currentTool = toolName;
    this.profile.set('currentTool', toolName || '');
  }

  toggleTool(toolName) {
    this.showTool(this.currentTool === toolName ? undefined : toolName);
  }

  showTool(toolName) {
    ToolLink.setActive();
    if (toolName) {
      const toolUri = $(ToolLink.selector + '[data-tool-name="' + toolName + '"]').data('tool-uri');
      if (toolUri) {
        $.ajax({
          type: 'GET',
          url: toolUri,
          success: function (content) {
            this.setCurrentTool(toolName);
            this.$el.html(content);
            this.$parent.addClass('tool-visible');
            ToolLink.setActive(toolName);
            this.onContentLoaded(undefined, this.$el);
          }.bind(this),
          error: function () {
            this.closeTool();
          }.bind(this),
          async: true,
          cache: false
        });
      } else {
        this.closeTool();
      }
    } else {
      this.closeTool();
    }
  }

  closeTool() {
    this.setCurrentTool();
    this.$parent.removeClass('tool-visible');
    this.$el.html('');
  }
}

CPM.widgets.register(BrowserTool);

class BrowserViewParameters extends ViewWidget {

  static selector = '.browser-page_browser_parameters .form-inline';

  constructor(element) {
    super(element);
    this.profile = new Profile('browser.parameters');
  }

  getUrlQuery(tabId) {
    if (tabId) {
      const formData = new FormData(this.el);
      const queryParams = new URLSearchParams(formData).toString()
        .replace(/[^=]+=&/, '')
        .replace(/&?[^=]+=$/, '');
      return queryParams ? '?' + queryParams : '';
    } else {
      return '';
    }
  }

  storeProfile(tabId) {
    if (tabId) {
      const formData = new FormData(this.el);
      const profileData = {};
      formData.forEach((value, key) => {
        if (value) {
          profileData[key] = value
        }
      });
      this.profile.set(tabId, profileData);
    }
  }

  contentUrl(tabId) {
    return this.$el.closest(BrowserView.selector).data('tab-form').replaceAll('#id#', tabId);
  }

  loadForm(tabId) {
    $.ajax({
      type: 'GET',
      url: this.contentUrl(tabId),
      success: function (content) {
        this.$el.html(content);
        // this form area's own content can carry widget-bearing markup (e.g. the Properties
        // view's toolbar, see PropertiesToolbar) - without this, nothing scoped to it would ever
        // actually attach, since inserting HTML via .html() alone never auto-initializes widgets
        CPM.widgets.initialize(this.$el);
        const profileData = this.profile.get(tabId);
        if (profileData) {
          Object.keys(profileData).forEach(key => {
            this.$el.find('select[name="' + key + '"] option[value="' + profileData[key] + '"]').attr('selected', true);
            this.$el.find('input[type="checkbox"][name="' + key + '"]').prop('checked', true);
            this.$el.find('input[type="text"][name="' + key + '"]').val(profileData[key]);
          });
        }
      }.bind(this),
      error: function () {
        this.$el.html('');
      }.bind(this),
      async: true,
      cache: false
    });
  }
}

CPM.widgets.register(BrowserViewParameters);

class BrowserView extends BrowserPanel {

  static selector = '.browser-page_browser_view';

  constructor(element) {
    super(element);
    this.$el.find('.browser-page_browser_action-reload').click(this.reload.bind(this));
    this.$el.find('.browser-page_browser_tabs a[data-bs-toggle="tab"]').on('shown.bs.tab', this.onTabShown.bind(this));
    this.parameters = Widgets.getView(BrowserViewParameters.selector, BrowserViewParameters);
    if (this.parameters) {
      this.parameters.$el.on('submit', this.reload.bind(this));
    }
    this.showTab(this.profile.get('currentTab'), true);
    $(document)
      .on('path:selected', this.onPathSelected.bind(this))
      // the currently shown tab (e.g. Properties) reflects the target resource's own state, which
      // any mutation may have changed - reload it exactly like a manual reload click, whether the
      // mutation was dialog-based ('dialog:success') or not ('changes:applied', see BrowserTree's
      // own listener for the same two events, which covers the tree side of this)
      .on('dialog:success', () => this.reload())
      .on('changes:applied', () => this.reload());
  }

  reload(event) {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    if (this.parameters) {
      this.parameters.storeProfile(this.activeTabId());
    }
    this.onPathSelected(event, this.currentPath, true);
    return false;
  }

  onPathSelected(event, path, force) {
    if (force || path !== this.currentPath) {
      this.currentPath = path;
      const $tab = this.$el.find('.browser-page_browser_tabs-content .tab-pane').data('loaded', 'false');
      this.loadContent(this.activeTabId());
    }
  }

  activeTabId() {
    const active = this.$el.find('.browser-page_browser_tabs .nav-link.active').attr('aria-controls');
    return active || this.$el.find('.browser-page_browser_tabs .nav-link').first().attr('aria-controls')
  }

  $tabPane(tabId) {
    return tabId ? this.$el.find('.browser-page_browser_tabs-content .tab-pane[id="' + tabId + '"]') : undefined;
  }

  onTabShown(event) {
    const tabId = $(event.target).attr('aria-controls');
    this.profile.set('currentTab', tabId);
    const $tab = this.$tabPane(tabId);
    this.parameters.loadForm(tabId);
    if ($tab.data('loaded') !== 'true') {
      this.loadContent(tabId);
    }
  }

  showTab(tabId, force) {
    const $tab = this.$el.find('.browser-page_browser_tabs .nav-link[aria-controls="' + tabId + '"]');
    const $target = $tab.length > 0 ? $tab : (force ? this.$el.find('.browser-page_browser_tabs .nav-link').first() : undefined);
    if ($target && $target.length > 0) {
      bootstrap.Tab.getOrCreateInstance($target[0]).show();
    }
  }

  contentUrl(contentId, tabId, path) {
    return (this.$el.data(contentId).replaceAll('#id#', tabId) + (path ? path : ''))
      + (this.parameters ? this.parameters.getUrlQuery(tabId) : '');
  }

  loadContent(tabId, callback) {
    if (this.currentPath) {
      window.setTimeout(function () {
        $.ajax({
          type: 'GET',
          url: this.contentUrl('tab-view', tabId, this.currentPath),
          success: function (content) {
            const $tab = this.$tabPane(tabId);
            $tab.html(content);
            $tab.data('loaded', 'true');
            this.onContentLoaded(undefined, $tab);
            if (callback) {
              callback();
            }
          }.bind(this),
          async: true,
          cache: false
        });
      }.bind(this), 100);
    }
  }

  onContentLoaded(event, element) {
    super.onContentLoaded(event, element);
    $(element || this.el).find('.preview iframe').on('load.preview', function (event) {
      var url = event.currentTarget.contentDocument.URL;
      //$(document).trigger('page:changed', [url]); // FIXME...
    }.bind(this));
  }
}

CPM.widgets.register(BrowserView);

class BrowserPage extends ViewWidget {

  static selector = '.browser-page_body';

  constructor(element) {
    super(element);
    this.tree = Widgets.getView(this.$(BrowserTree.selector), BrowserTree);
  }

  getCurrentPath() {
    return this.tree.getSelectedPath();
  }
}

CPM.widgets.register(BrowserPage);

// The "Change" dropdown (Create/Delete/Move/Copy/Paste) plus a "Reload" button - the node-level
// toolbar above the tree (see browser/node-toolbar.html). The toolbar container itself is always
// present; only the "Edit" dropdown's markup is conditional on browser.writeEnabled (true only
// while a ChangesService is bound) - Reload is a read-only convenience unrelated to whether
// mutations are possible at all, so it always renders regardless. Previously the "Edit" navbar
// dropdown, moved here for the same reason the Properties toolbar moved out of its own view
// content: a dedicated action area next to what it operates on (the tree's current selection)
// rather than tucked into the global navbar - kept as an actual dropdown (not spread into flat
// always-visible buttons) since these are comparatively rare, deliberate actions, unlike the
// always-relevant Reload alongside it. Create/Delete/Move/Paste all open dialog fragments served by
// ChangesService through the shared 'CPM.Dialog'/'DialogForm' framework - Paste opens one too
// (rather than a plain POST) so its target name can be adjusted, which is what makes duplicating a
// node into its own parent via Copy/Paste possible at all; Copy itself is entirely client-side
// (remembers the current path in the same 'Profile' local-storage-backed store the rest of Browser
// already uses for its own per-session state - no server-side clipboard state exists). Reload
// re-fetches the currently selected tree node's own children from the server (see
// BrowserTree#reloadSelected) - independent of any particular mutation, useful whenever the
// repository changed by some other means the Browser has no way to know about on its own.
class BrowserNodeToolbar extends ViewWidget {

  static selector = '.browser-node-toolbar';

  constructor(element) {
    super(element);
    this.profile = new Profile('browser');
    this.dialogUri = this.$el.data('dialog-uri');
    $(document).on('path:selected', this.onPathSelected.bind(this));
    this.$el.find('.browser-node_action-create').on('click', (event) => {
      event.preventDefault();
      new CPM.Dialog(this.dialogUri + 'create.html' + this.path).open();
    });
    this.$el.find('.browser-node_action-delete').on('click', (event) => {
      event.preventDefault();
      new CPM.Dialog(this.dialogUri + 'delete.html' + this.path).open();
    });
    this.$el.find('.browser-node_action-move').on('click', (event) => {
      event.preventDefault();
      new CPM.Dialog(this.dialogUri + 'move.html' + this.path).open();
    });
    this.$el.find('.browser-node_action-copy').on('click', (event) => {
      event.preventDefault();
      this.profile.set('clipboard', this.path);
    });
    this.$el.find('.browser-node_action-paste').on('click', (event) => {
      event.preventDefault();
      this.paste();
    });
    this.$el.find('.browser-node_action-reload').on('click', (event) => {
      event.preventDefault();
      const tree = Widgets.getView(BrowserTree.selector, BrowserTree);
      if (tree) {
        tree.reloadSelected();
      }
    });
  }

  onPathSelected(event, path) {
    this.path = path;
  }

  paste() {
    const source = this.profile.get('clipboard');
    if (!source || !this.path) {
      return;
    }
    new CPM.Dialog(this.dialogUri + 'paste.html' + this.path + '?source=' + encodeURIComponent(source)).open();
  }
}

CPM.widgets.register(BrowserNodeToolbar);

// The Properties table's own checkbox selection (see view/properties/properties.html) - reports
// it to PropertiesToolbar below via a document-level event rather than direct DOM access, since
// the two no longer share a scope: this table is reloaded on every path navigation (a fresh
// '.browser-properties' element each time, always starting unselected - hence the initial report,
// which correctly resets the toolbar whenever a different resource's properties are shown), while
// the toolbar (in the separate, tab-level "form" area) is not.
class PropertiesSelection extends ViewWidget {

  static selector = '.browser-properties';

  constructor(element) {
    super(element);
    this.$el.on('change', '.browser-properties_select', () => this.report());
    // a convenience click target - the checkbox column itself is narrow, so toggling via the
    // (much wider) name cell instead is much easier to hit; only present at all while
    // 'browser.writeEnabled' rendered the checkbox column in the first place, so no extra guard
    // is needed here beyond the checkbox actually existing in that row
    this.$el.on('click', '.property-name', (event) => {
      $(event.currentTarget).closest('tr').find('.browser-properties_select')
        .prop('checked', (i, checked) => !checked).trigger('change');
    });
    this.report();
  }

  report() {
    const names = this.$el.find('.browser-properties_select:checked').map((i, el) => el.value).get();
    $(document).trigger('properties:selected', [names]);
  }
}

CPM.widgets.register(PropertiesSelection);

// The Properties view's toolbar - now rendered once per tab-show, via the generic per-tab "form"
// area (see view/properties/form.html) rather than as part of the properties table itself, so it
// survives a path navigation instead of being torn down and recreated with every table reload.
// One consequence: the "form" request that renders this fragment carries no target path of its
// own at all (it is the same generic mechanism every view's parameter form uses), so unlike the
// old combined widget this can't just read a 'data-path' attribute - it tracks the current path
// itself via the global 'path:selected' event, exactly like BrowserNodeToolbar does,
// seeded with the tree's *current* selection at construction time (via 'BrowserTree#getSelectedPath')
// so a tab-switch-away-and-back (which recreates this widget, unlike a plain path navigation)
// doesn't leave it without a path until the next explicit selection. Selection state itself comes
// from PropertiesSelection above, since the checkboxes it watches live in a different, more
// often-reloaded part of the DOM. Add/Edit open the same 'property' dialog (empty resp. pre-filled
// via a '?name=' query param); Copy/Paste are a plain client-side clipboard (remembers the source
// path + selected names in the same 'Profile' store the rest of Browser already uses - the same
// pattern as node-level Copy/Paste, see BrowserNodeToolbar - "Copy" itself never reaches the server);
// Delete is a plain confirm()-then-POST, deliberately not the shared 'CPM.Dialog' confirm fragment
// (the message needs to name the *variable* number of selected properties, which the shared,
// statically-rendered dialog can't parameterize without a server round-trip just to build a sentence).
class PropertiesToolbar extends ViewWidget {

  static selector = '.browser-properties-form';

  constructor(element) {
    super(element);
    this.profile = new Profile('browser');
    this.dialogUri = this.$el.data('dialog-uri');
    this.copyUri = this.$el.data('copy-uri');
    this.deleteUri = this.$el.data('delete-uri');
    this.selectedNames = [];
    const tree = Widgets.getView(BrowserTree.selector, BrowserTree);
    this.path = tree ? tree.getSelectedPath() : undefined;
    this.$el.find('.browser-properties_action-add').on('click', (event) => {
      event.preventDefault();
      this.openDialog();
    });
    this.$el.find('.browser-properties_action-edit').on('click', (event) => {
      event.preventDefault();
      if (this.selectedNames.length === 1) {
        this.openDialog(this.selectedNames[0]);
      }
    });
    this.$el.find('.browser-properties_action-copy').on('click', (event) => {
      event.preventDefault();
      this.copySelection();
    });
    this.$el.find('.browser-properties_action-paste').on('click', (event) => {
      event.preventDefault();
      this.paste();
    });
    this.$el.find('.browser-properties_action-delete').on('click', (event) => {
      event.preventDefault();
      this.deleteSelection();
    });
    $(document)
      .on('path:selected', (event, path) => {
        this.path = path;
      })
      .on('properties:selected', (event, names) => {
        this.selectedNames = names;
        this.updateToolbar();
      });
    this.updateToolbar();
  }

  updateToolbar() {
    const names = this.selectedNames;
    const clipboard = this.profile.get('propertyClipboard');
    this.$el.find('.browser-properties_action-edit').prop('disabled', names.length !== 1);
    this.$el.find('.browser-properties_action-copy').prop('disabled', names.length === 0);
    this.$el.find('.browser-properties_action-delete').prop('disabled', names.length === 0);
    this.$el.find('.browser-properties_action-paste')
      .prop('disabled', !clipboard || !clipboard.names || clipboard.names.length === 0);
  }

  openDialog(name) {
    const url = this.dialogUri + 'property.html' + this.path + (name ? '?name=' + encodeURIComponent(name) : '');
    // the dialog's own "Remove" button (see changes/script.js's 'PropertyRemove' widget) self-wires
    // the moment the fragment is inserted into the DOM - nothing to do here
    new CPM.Dialog(url).open();
  }

  copySelection() {
    const names = this.selectedNames;
    if (names.length > 0) {
      this.profile.set('propertyClipboard', {path: this.path, names: names});
      this.updateToolbar();
    }
  }

  paste() {
    const clipboard = this.profile.get('propertyClipboard');
    if (!clipboard || !clipboard.names || clipboard.names.length === 0) {
      return;
    }
    // 'traditional' (the 2nd arg) is required: jQuery's default array serialization uses
    // 'names[]=a&names[]=b', but the server reads a plain multi-value 'names' parameter
    // ('request.getParameterValues("names")'), which needs 'names=a&names=b' instead
    const data = $.param({source: clipboard.path, names: clipboard.names}, true);
    $.ajax({
      type: 'POST',
      url: this.copyUri + this.path,
      data: data,
      contentType: 'application/x-www-form-urlencoded',
      success: (result) => $(document).trigger('changes:applied', [result]),
      error: (jqXHR) => alert((jqXHR.responseJSON && jqXHR.responseJSON.message) || 'Paste failed.'),
      async: true,
      cache: false
    });
  }

  deleteSelection() {
    const names = this.selectedNames;
    if (names.length === 0) {
      return;
    }
    const label = names.length === 1 ? `'${names[0]}'` : `${names.length} properties`;
    if (!confirm(`Delete ${label}?`)) {
      return;
    }
    $.ajax({
      type: 'POST',
      url: this.deleteUri + this.path,
      // see the 'paste' method above for why 'traditional' (2nd arg) is required here too
      data: $.param({names: names}, true),
      contentType: 'application/x-www-form-urlencoded',
      success: (result) => $(document).trigger('changes:applied', [result]),
      error: (jqXHR) => alert((jqXHR.responseJSON && jqXHR.responseJSON.message) || 'Delete failed.'),
      async: true,
      cache: false
    });
  }
}

CPM.widgets.register(PropertiesToolbar);
