class PackagesTree extends ViewWidget {

  static selector = '.packages-page_tree';

  constructor(element) {
    super(element);
    this.mode = this.$el.data('mode');
    const treeOptions = {
      'plugins': ['types', 'unique', 'wholerow'],
      'core': {
        'animation': false,
        'data': this.nodeData.bind(this),
        'cache': false,
        'multiple': false,
        'force_text': true,
        'themes': {'name': 'proton'}
      },
      'types': {
        // no 'root' type - the synthetic root node is never rendered, see #nodeData
        'folder': {'icon': 'bi bi-folder'},
        'package': {'icon': 'bi bi-file-earmark-zip'}
      }
    };
    this.$jstree = this.$el.jstree(treeOptions);
    this.jstree = this.$el.jstree(true);
    this.$jstree.on('select_node.jstree', this.onNodeSelected.bind(this));
    $(document)
      .on('packages:refresh', this.onRefresh.bind(this))
      .on('path:select', this.doSelectPath.bind(this));
    // pre-select the path the page was opened with (a bookmark, a reload, or the initial load
    // after CPM.history restored a previously pushed URL) - a short delay lets the freshly
    // created jstree instance settle before it is asked to drill down
    const path = this.$el.data('path');
    if (path && path !== '/') {
      setTimeout(() => this.openNode(path, true), 300);
    }
  }

  get modeQuery() {
    return this.mode === 'registry' ? '?mode=registry' : '';
  }

  onNodeSelected(event, data) {
    const node = data.node;
    if (!node.original) {
      return;
    }
    if (node.original.type !== 'package') {
      // an intermediate (group/name) node: always ensure it is open - jstree's own 'open_node'
      // is a no-op if it already is, so a click here never collapses it again, only expands
      this.jstree.open_node(node);
    }
    if (this.suppressEvent) {
      $(document).trigger('package:selected', [node.original.path]);
    } else {
      this.triggerPathSelected(node.original.path);
    }
  }

  // fires the selection event and pushes the path onto the browser history, so back/forward
  // navigation works for tree selection just like it does for the Browser's resource tree
  triggerPathSelected(path) {
    $(document).trigger('package:selected', [path]);
    CPM.history.pushUri(this.pageUrl(path));
  }

  pageUrl(path) {
    return this.$el.data('page-url') + path;
  }

  onRefresh(event, path) {
    if (path && path !== '/') {
      this.$jstree.one('refresh.jstree', () => this.openNode(path, true));
    }
    this.jstree.refresh();
  }

  getSelectedNode() {
    const selectedIds = this.jstree.get_selected();
    return selectedIds.length > 0 ? this.jstree.get_node(selectedIds[0]) : undefined;
  }

  // re-selects the given path in the tree, e.g. on browser back/forward (see the 'path:select'
  // listener above) or after an edit that may have renamed (moved) the currently shown package
  // (see PackagesDetail#onDialogSuccess) - 'suppressEvent' skips the selection event/history push
  // this would otherwise itself trigger, for callers that already know about (or don't need) it
  doSelectPath(event, path) {
    const selected = this.getSelectedNode();
    if (!selected || !selected.original || path !== selected.original.path) {
      this.openNode(path);
    }
  }

  openNode(path, suppressEvent) {
    if (!path || path === '/') {
      return;
    }
    this.jstree.deselect_all();
    $.ajax({
      type: 'GET',
      url: this.$el.data('ancestors-url') + path + this.modeQuery,
      success: (ancestors) => this.drilldown(ancestors.concat([path]), 0, suppressEvent),
      async: true,
      cache: false
    });
  }

  // opens each ancestor folder in turn (which lazily loads/renders its children into the DOM,
  // making the next level's node findable) before finally selecting the leaf itself
  drilldown(chain, index, suppressEvent) {
    const id = this.nodeId(chain[index]);
    const $node = this.$el.find('#' + id);
    if ($node.length === 0) {
      return;
    }
    this.jstree.open_node($node, () => {
      if (index + 1 < chain.length) {
        this.drilldown(chain, index + 1, suppressEvent);
      } else {
        this.suppressEvent = suppressEvent;
        try {
          this.jstree.select_node($node);
        } finally {
          delete this.suppressEvent;
        }
        this.scrollIntoView($node);
      }
    });
  }

  scrollIntoView($node) {
    const $panel = this.$el.closest('.packages-page_tree-panel');
    const nodePos = $node.position();
    if (nodePos && $panel.length > 0) {
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

  dataUrl(node) {
    const path = (node.original && node.original.path) ? node.original.path : '/';
    return this.$el.data('tree-url') + path + this.modeQuery;
  }

  // jstree node ids must be valid CSS selector fragments - path is arbitrary, so it is base64-encoded
  nodeId(path) {
    return ('CPKG_' + btoa(encodeURIComponent(path))).replace(/=/g, '-').replace(/\//g, '_');
  }

  nodeData(node, callback) {
    const tree = this;
    $.ajax({
      type: 'GET',
      url: tree.dataUrl(node),
      success: function (result) {
        result.id = tree.nodeId(result.path);
        result.state = Object.assign({opened: node.id === '#'}, result.state);
        if (result.children) {
          result.children.forEach(function (child) {
            child.id = tree.nodeId(child.path);
          });
        }
        // jstree never renders a node for the '#' request itself - returning its children
        // directly (instead of the single synthetic 'root' node wrapping them) hides that
        // wrapper, so the tree starts right at the top-level groups
        callback.call(tree.$jstree, node.id === '#' ? (result.children || []) : result);
      },
      error: function () {
        callback.call(tree.$jstree, []);
      },
      async: true,
      cache: false
    });
  }
}

CPM.widgets.register(PackagesTree);

class PackagesToolbar extends ViewWidget {

  static selector = '.packages-page_toolbar';

  constructor(element) {
    super(element);
    this.dialogUrl = this.$el.data('dialog-url');
    this.$el.find('.packages-page_action-create').on('click', () => new CPM.Dialog(this.dialogUrl + 'create.html').open());
    this.$el.find('.packages-page_action-upload').on('click', () => new CPM.Dialog(this.dialogUrl + 'upload.html').open());
  }
}

CPM.widgets.register(PackagesToolbar);

// the Filters dialog's dynamic list of filter-root blocks (root path, import mode, include/
// exclude rules) - 'Add filter root' appends a fresh, empty block (its fields share the same
// 'root'/'mode'/'rules' names as the server-rendered ones, submitted as index-aligned arrays in
// DOM order, see JcrPackageOperations#setFilters - so Up/Down (moving a block among its DOM
// siblings) is all it takes to reorder the filter, no separate index bookkeeping needed. All of
// this is event-delegated, so it works on blocks added after the dialog was opened too. Still
// just one form, one submit.
class PackagesFilterRoots extends ViewWidget {

  static selector = '.packages-page_filter-roots';

  static blockSelector = '.packages-page_filter-root';

  static template = '<div class="packages-page_filter-root">'
    + '<div class="row g-2 align-items-end">'
    + '<div class="col-6"><label class="form-label">Root</label>'
    + '<input type="text" class="form-control" name="root" value=""></div>'
    + '<div class="col-3"><label class="form-label">Import Mode</label>'
    + '<select class="form-control" name="mode">'
    + '<option value="REPLACE">replace (default)</option>'
    + '<option value="MERGE">merge (existing content is not modified)</option>'
    + '<option value="UPDATE">update (existing content is not deleted)</option>'
    + '</select></div>'
    + '<div class="col-3"><label class="form-label d-block">&nbsp;</label>'
    + '<div class="btn-group" role="group">'
    + '<button type="button" class="btn btn-outline-secondary packages-page_filter-root-up" title="Move up"><i class="bi bi-arrow-up"></i></button>'
    + '<button type="button" class="btn btn-outline-secondary packages-page_filter-root-down" title="Move down"><i class="bi bi-arrow-down"></i></button>'
    + '<button type="button" class="btn btn-outline-danger packages-page_filter-root-remove" title="Remove"><i class="bi bi-trash"></i></button>'
    + '</div></div>'
    + '</div>'
    + '<textarea class="form-control packages-page_filters-rules" name="rules" rows="3" spellcheck="false" '
    + 'placeholder="+ include-pattern&#10;- exclude-pattern"></textarea>'
    + '</div>';

  constructor(element) {
    super(element);
    this.$el.on('click', '.packages-page_filter-root-remove', function (event) {
      $(event.currentTarget).closest(PackagesFilterRoots.blockSelector).remove();
    });
    this.$el.on('click', '.packages-page_filter-root-up', function (event) {
      const $block = $(event.currentTarget).closest(PackagesFilterRoots.blockSelector);
      const $prev = $block.prev(PackagesFilterRoots.blockSelector);
      if ($prev.length > 0) {
        $block.insertBefore($prev);
      }
    });
    this.$el.on('click', '.packages-page_filter-root-down', function (event) {
      const $block = $(event.currentTarget).closest(PackagesFilterRoots.blockSelector);
      const $next = $block.next(PackagesFilterRoots.blockSelector);
      if ($next.length > 0) {
        $block.insertAfter($next);
      }
    });
    this.$el.next('.packages-page_filter-root-add').on('click', () => {
      this.$el.append($(PackagesFilterRoots.template));
    });
  }
}

CPM.widgets.register(PackagesFilterRoots);

// the detail panel for the currently selected package: the server renders the whole thing -
// property table and the action bar alike (see PackageManager#actions, details/content.html) -
// this widget only loads that HTML on selection and wires the action buttons' click handlers to
// the shared 'CPM.Dialog' framework; it builds no markup of its own.
class PackagesDetail extends ViewWidget {

  static selector = '.packages-page_detail-panel';

  constructor(element) {
    super(element);
    this.mode = this.$el.data('mode');
    this.dialogUrl = this.$el.data('dialog-url');
    $(document).on('package:selected', this.onPackageSelected.bind(this));
    $(document).on('dialog:success', this.onDialogSuccess.bind(this));
  }

  get modeQuery() {
    return this.mode === 'registry' ? '?mode=registry' : '';
  }

  onPackageSelected(event, path) {
    this.path = path;
    this.load();
  }

  load() {
    $.ajax({
      type: 'GET',
      url: this.$el.data('view-url') + this.path + this.modeQuery,
      success: (content) => {
        this.$el.html(content);
        this.onContentLoaded(undefined, this.$el);
      },
      error: () => {
        this.$el.html('<p class="text-danger">This package could not be loaded.</p>');
      },
      async: true,
      cache: false
    });
  }

  onContentLoaded(event, element) {
    super.onContentLoaded(event, element);
    // every action button/link is server-rendered with a 'packages-page_action-<key>' class
    // (see details/action.html); the ones that open a dialog all use the same mode-agnostic
    // dialog file, just with '?mode=registry' appended when the package is registry-backed -
    // 'download' is a plain link with its href already set server-side, no handler needed here
    ['edit:update', 'filters', 'install', 'uninstall', 'assemble', 'coverage', 'delete', 'purge']
      .forEach((entry) => {
        const [actionKey, dialogName] = entry.split(':');
        const dialog = (dialogName || actionKey) + '.html';
        this.$el.find('.packages-page_action-' + actionKey).on('click',
          () => new CPM.Dialog(this.dialogUrl + dialog + this.path + this.modeQuery).open());
      });
    // an intermediate (group/name) node's list view (see details/folder.html): clicking an
    // entry navigates straight to it, exactly like clicking the same package in the tree would
    this.$el.find('.packages-page_folder-entry-link').on('click', function (event) {
      event.preventDefault();
      const path = $(event.currentTarget).data('path');
      if (path) {
        $(document).trigger('path:select', [path]);
      }
    });
  }

  onDialogSuccess(event, el, result) {
    if (result && result.deleted) {
      $(document).trigger('packages:refresh');
      this.path = undefined;
      this.$el.html('<p class="packages-page_hint">Package deleted - select another version in the tree.</p>');
    } else {
      if (result && result.path) {
        // e.g. a group/name/version change in the Edit dialog renames (moves) the package -
        // follow it, or the next reload would ask for a path that no longer exists
        this.path = result.path;
      }
      // refreshes the tree and, once done, re-selects (highlights) this.path there too - so
      // after Install/Uninstall/Build/Edit/... the detail view and the tree selection agree
      // again on which package is showing, even if editing renamed/moved it
      $(document).trigger('packages:refresh', [this.path]);
      if (this.path) {
        this.load();
      }
    }
  }
}

CPM.widgets.register(PackagesDetail);
