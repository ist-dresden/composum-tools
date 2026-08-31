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
        'root': {'icon': 'bi bi-diagram-3'},
        'folder': {'icon': 'bi bi-folder'},
        'package': {'icon': 'bi bi-file-earmark-zip'}
      }
    };
    this.$jstree = this.$el.jstree(treeOptions);
    this.jstree = this.$el.jstree(true);
    this.$jstree.on('select_node.jstree', this.onNodeSelected.bind(this));
    $(document).on('packages:refresh', this.onRefresh.bind(this));
  }

  get modeQuery() {
    return this.mode === 'registry' ? '?mode=registry' : '';
  }

  onNodeSelected(event, data) {
    const node = data.node;
    if (node.original && node.original.type === 'package') {
      $(document).trigger('package:selected', [node.original.path]);
    }
  }

  onRefresh() {
    this.jstree.refresh();
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
        callback.call(tree.$jstree, result);
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
// 'root'/'mode'/'rules' names as the server-rendered ones, submitted as index-aligned arrays,
// see JcrPackageOperations#setFilters); 'Remove' (event-delegated, so it also works on blocks
// added after the dialog was opened) removes its own block. Still just one form, one submit.
class PackagesFilterRoots extends ViewWidget {

  static selector = '.packages-page_filter-roots';

  static template = '<div class="packages-page_filter-root">'
    + '<div class="row g-2 align-items-end">'
    + '<div class="col-7"><label class="form-label">Root</label>'
    + '<input type="text" class="form-control" name="root" value=""></div>'
    + '<div class="col-3"><label class="form-label">Import Mode</label>'
    + '<input type="text" class="form-control" name="mode" value="" list="packages-filters_mode-options"></div>'
    + '<div class="col-2"><button type="button" class="btn btn-outline-danger packages-page_filter-root-remove">Remove</button></div>'
    + '</div>'
    + '<textarea class="form-control packages-page_filters-rules" name="rules" rows="3" spellcheck="false" '
    + 'placeholder="+ include-pattern&#10;- exclude-pattern"></textarea>'
    + '</div>';

  constructor(element) {
    super(element);
    this.$el.on('click', '.packages-page_filter-root-remove', function (event) {
      $(event.currentTarget).closest('.packages-page_filter-root').remove();
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
    ['edit:update', 'filters', 'install', 'uninstall', 'assemble', 'coverage', 'delete']
      .forEach((entry) => {
        const [actionKey, dialogName] = entry.split(':');
        const dialog = (dialogName || actionKey) + '.html';
        this.$el.find('.packages-page_action-' + actionKey).on('click',
          () => new CPM.Dialog(this.dialogUrl + dialog + this.path + this.modeQuery).open());
      });
  }

  onDialogSuccess(event, el, result) {
    $(document).trigger('packages:refresh');
    if (result && result.deleted) {
      this.path = undefined;
      this.$el.html('<p class="packages-page_hint">Package deleted - select another version in the tree.</p>');
    } else if (this.path) {
      this.load();
    }
  }
}

CPM.widgets.register(PackagesDetail);
