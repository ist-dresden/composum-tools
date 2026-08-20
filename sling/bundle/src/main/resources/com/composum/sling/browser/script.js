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
        this.lastAction()
        this.$el.find('.dropdown-item').on('click', (event) => {
          const $action = $(event.currentTarget);
          this.profile.set('lastAction', $action.data('key'));
          this.lastAction()
        })
      });
    }
  }

  lastAction() {
    const key = this.profile.get('lastAction');
    let $item = key ? this.$el.find(`.dropdown-menu .action-${key}`) : [];
    if ($item.length === 0) {
      $item = this.$el.find('.dropdown-menu li:first-child');
    }
    if ($item.length > 0) {
      const $action = $item.find('a');
      this.$currentAction.data('key', $action.data('key'));
      this.$currentAction.attr('href', $action.attr('href'));
      this.$currentAction.attr('title', $action.attr('title'));
      this.$currentAction.attr('target', $action.attr('target'));
      this.$currentAction.html($action.html());
    }
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
      .on('path:select', this.doSelectPath.bind(this));
    const path = this.$el.data('path');
    if (path) {
      setTimeout(function () {
        this.openNode(path, function (path) {
          $(document).trigger('path:selected', [path]);
        }.bind(this), true);
      }.bind(this), 400);
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

  dataUrl(node) {
    const path = node.original && node.original.path ? node.original.path : '/';
    return this.$el.data('tree-url') + path;
  }

  nodeData(node, callback) {
    const tree = this;
    $.ajax({
      type: 'GET',
      url: tree.dataUrl(node),
      success: function (result, msg, xhr) {
        result.id = tree.nodeId(result.path);
        if (result.children) {
          for (let i = 0; i < result.children.length; i++) {
            result.children[i].id = tree.nodeId(result.children[i].path);
          }
        }
        callback.call(tree.$jstree, result);
      },
      async: true,
      cache: false
    });
  }

  nodeId(id) {
    if (id && (typeof id !== 'string' || id.indexOf('CBT_') !== 0)) {
      if (Array.isArray(id)) id = id.join('/');
      id = ('CBT_' + btoa(encodeURIComponent(id))).replace(/=/g, '-').replace(/\//g, '_');
    }
    return id;
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
            this.onContentLoaded(this.$el);
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
    $(document).on('path:selected', this.onPathSelected.bind(this));
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
    if ($tab.length > 0) {
      $tab.tab('show');
    } else if (force) {
      this.$el.find('.browser-page_browser_tabs .nav-link').first().tab('show');
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
            this.onContentLoaded($tab);
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
    super.onContentLoaded(element);
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
