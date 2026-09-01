// the '/home/users'/'/home/groups' tree - lazily loaded per node (see UserManager#treeNode /
// jcr.JcrAuthorizableTree), same jstree lazy-load JSON contract and synthetic-root-hiding
// convention as the Package Manager's own tree (see packages/script.js#PackagesTree), just
// without any mode toggle since there is only one backend here.
class UsersTree extends ViewWidget {

  static selector = '.usermgr-page_tree';

  constructor(element) {
    super(element);
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
        'user': {'icon': 'bi bi-person'},
        'system-user': {'icon': 'bi bi-gear'},
        'group': {'icon': 'bi bi-people'}
      }
    };
    this.$jstree = this.$el.jstree(treeOptions);
    this.jstree = this.$el.jstree(true);
    this.$jstree.on('select_node.jstree', this.onNodeSelected.bind(this));
    $(document)
      .on('users:refresh', this.onRefresh.bind(this))
      .on('path:select', this.doSelectPath.bind(this));
    // pre-select the path the page was opened with (a bookmark, a reload, or the initial load
    // after CPM.history restored a previously pushed URL) - a short delay lets the freshly
    // created jstree instance settle before it is asked to drill down
    const path = this.$el.data('path');
    if (path && path !== '/') {
      setTimeout(() => this.openNode(path, true), 300);
    }
  }

  onNodeSelected(event, data) {
    const node = data.node;
    if (!node.original) {
      return;
    }
    if (node.original.type === 'folder') {
      // an intermediate folder: always ensure it is open - jstree's own 'open_node' is a no-op
      // if it already is, so a click here never collapses it again, only expands
      this.jstree.open_node(node);
    }
    if (this.suppressEvent) {
      $(document).trigger('authorizable:selected', [node.original.path]);
    } else {
      this.triggerPathSelected(node.original.path);
    }
  }

  // fires the selection event and pushes the path onto the browser history, so back/forward
  // navigation works for tree selection just like it does for the Browser's resource tree
  triggerPathSelected(path) {
    $(document).trigger('authorizable:selected', [path]);
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
  // listener above) or after an edit that may have changed the currently shown authorizable
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
      url: this.$el.data('ancestors-url') + path,
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
    const $panel = this.$el.closest('.usermgr-page_tree-panel');
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
    return this.$el.data('tree-url') + path;
  }

  // jstree node ids must be valid CSS selector fragments - path is arbitrary, so it is base64-encoded
  nodeId(path) {
    return ('CUSR_' + btoa(encodeURIComponent(path))).replace(/=/g, '-').replace(/\//g, '_');
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
        // wrapper, so the tree starts right at 'Users'/'Groups'
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

CPM.widgets.register(UsersTree);

// the tree-bar "Find" input: a small debounced-search dropdown backed by
// UserManager#query/JcrAuthorizableOperations#find (an indexed authorizable-id search) - this is
// deliberately not a Package-Manager-style recursive folder listing, since '/home' can hold
// thousands of authorizables nested arbitrarily deep with no index behind a plain tree walk.
// Selecting a result reuses the same 'path:select' event a folder-entry click already uses, so
// the tree drills open and the detail panel loads exactly like any other navigation.
class UsersSearch extends ViewWidget {

  static selector = '.usermgr-page_search';

  static minLength = 2;

  static debounceMillis = 250;

  constructor(element) {
    super(element);
    this.queryUrl = this.$el.data('query-url');
    this.$input = this.$el.find('.usermgr-page_search-input');
    this.$results = this.$el.find('.usermgr-page_search-results');
    this.$input.on('input', this.onInput.bind(this));
    $(document).on('click', (event) => {
      if (!$.contains(this.el, event.target)) {
        this.hideResults();
      }
    });
  }

  onInput() {
    const text = this.$input.val().trim();
    if (this.timer) {
      clearTimeout(this.timer);
    }
    if (text.length < UsersSearch.minLength) {
      this.hideResults();
      return;
    }
    this.timer = setTimeout(() => this.search(text), UsersSearch.debounceMillis);
  }

  search(text) {
    $.ajax({
      type: 'GET',
      url: this.queryUrl + '?text=' + encodeURIComponent(text),
      success: (results) => this.showResults(results),
      async: true,
      cache: false
    });
  }

  showResults(results) {
    this.$results.empty();
    if (!results || results.length === 0) {
      this.$results.append($('<li class="usermgr-page_search-empty"></li>').text('No matches'));
    } else {
      results.forEach((ref) => {
        const $link = $('<a href="#"></a>')
          .append($('<i></i>').addClass('bi bi-' + ref.icon))
          .append(' ' + ref.label)
          .on('click', (event) => {
            event.preventDefault();
            $(document).trigger('path:select', [ref.path]);
            this.hideResults();
            this.$input.val('');
          });
        this.$results.append($('<li></li>').append($link));
      });
    }
    this.$results.removeClass('d-none');
  }

  hideResults() {
    this.$results.addClass('d-none').empty();
  }
}

CPM.widgets.register(UsersSearch);

// Create User / Create System User / Create Group buttons above the tree - shown only when
// writeEnabled (see page.html), same on-demand-dialog pattern as the Package Manager's own
// toolbar (packages/script.js#PackagesToolbar).
class UsersToolbar extends ViewWidget {

  static selector = '.usermgr-page_toolbar';

  constructor(element) {
    super(element);
    this.dialogUrl = this.$el.data('dialog-url');
    ['createUser', 'createSystemUser', 'createGroup'].forEach((key) => {
      this.$el.find('.usermgr-page_action-' + key).on('click',
        () => new CPM.Dialog(this.dialogUrl + key + '.html').open());
    });
  }
}

CPM.widgets.register(UsersToolbar);

// the detail panel for the currently selected authorizable (or intermediate folder): the server
// renders the whole thing - property table, tabs and action bar alike (see UserManager#actions,
// details/user.html, details/group.html, details/folder.html) - this widget only loads that HTML
// on selection and wires the action buttons' click handlers to the shared 'CPM.Dialog' framework,
// exactly like PackagesDetail#onContentLoaded.
class UsersDetail extends ViewWidget {

  static selector = '.usermgr-page_detail-panel';

  constructor(element) {
    super(element);
    this.pageUrl = this.$el.data('page-url');
    this.dialogUrl = this.$el.data('dialog-url');
    $(document).on('authorizable:selected', this.onAuthorizableSelected.bind(this));
    $(document).on('dialog:success', this.onDialogSuccess.bind(this));
  }

  onAuthorizableSelected(event, path) {
    this.path = path;
    this.load();
  }

  load() {
    $.ajax({
      type: 'GET',
      url: this.$el.data('view-url') + this.path,
      success: (content) => {
        this.$el.html(content);
        this.onContentLoaded(undefined, this.$el);
      },
      error: () => {
        this.$el.html('<p class="text-danger">This could not be loaded.</p>');
      },
      async: true,
      cache: false
    });
  }

  onContentLoaded(event, element) {
    super.onContentLoaded(event, element);
    // every action button (top action bar, and the Groups/Members tabs' own "Add" buttons alike)
    // is server-rendered with a 'usermgr-page_action-<key>' class (see details/action.html) and
    // opens the same kind of on-demand dialog, keyed off the current selection's path only
    ['enable', 'disable', 'password', 'delete', 'addToGroup', 'addMember', 'affectedPaths'].forEach((key) => {
      this.$el.find('.usermgr-page_action-' + key).on('click',
        () => new CPM.Dialog(this.dialogUrl + key + '.html' + this.path).open());
    });
    // a Groups/Members tab row's "Remove" button - unlike the buttons above, this needs the
    // specific row's authorizable id plus which side of the relationship it's on ('role', see
    // UserManager#changeMembership), read off the row's own markup (details/groupsEntry.html /
    // membersEntry.html)
    this.$el.find('.usermgr-page_membership-remove').on('click', (event) => {
      const $button = $(event.currentTarget);
      const role = $button.closest('.usermgr-page_membership-entry').data('role');
      const id = $button.data('id');
      new CPM.Dialog(this.dialogUrl + 'removeFromGroup.html' + this.path
        + '?authorizableId=' + encodeURIComponent(id) + '&role=' + role).open();
    });
    // an intermediate (folder) node's list view, and every Groups/Members tab row alike: click
    // navigates straight to that path, exactly like clicking the same node in the tree would
    this.$el.find('.usermgr-page_folder-entry-link').on('click', function (event) {
      event.preventDefault();
      const path = $(event.currentTarget).data('path');
      if (path) {
        $(document).trigger('path:select', [path]);
      }
    });
  }

  onDialogSuccess(event, el, result) {
    if (result && result.deleted) {
      // an authorizable's parent folder is a real, persistent JCR node - unlike a package's
      // synthetic tree path, it's never pruned just because it becomes empty, so (unlike
      // PackagesDetail's delete handling) 'result.parent' is always present here; the page-reload
      // fallback is kept anyway, purely defensive
      if (result.parent) {
        this.path = result.parent;
        $(document).trigger('users:refresh', [this.path]);
        this.load();
      } else {
        window.location.href = this.pageUrl;
      }
    } else {
      // e.g. Create User/System User/Group (from the toolbar), Enable/Disable, Change Password -
      // refresh the tree so the change shows up there too, and follow 'result.path' if the
      // operation resolves to one (a fresh create does; enable/disable/password just echo the
      // unchanged path back)
      if (result && result.path) {
        this.path = result.path;
      }
      $(document).trigger('users:refresh', [this.path]);
      if (this.path) {
        this.load();
      }
    }
  }
}

CPM.widgets.register(UsersDetail);

// the Change Password dialog's "Confirm Password" field: a client-side "passwords match" check
// only - the field has no 'name' the server would ever see (see dialogs/password.html); scoped
// to its own dialog markup like PackagesFilterRoots is, so it just works whenever the dialog's
// fragment is initialized (CPM.Dialog#open already calls CPM.widgets.initialize on it).
class PasswordConfirm extends ViewWidget {

  static selector = '.usermgr-page_password-confirm';

  constructor(element) {
    super(element);
    this.$password = this.$el.closest('form').find('[name="password"]');
    const check = () => {
      this.el.setCustomValidity(this.el.value === this.$password.val() ? '' : 'Passwords do not match.');
    };
    this.$el.on('input', check);
    this.$password.on('input', check);
  }
}

CPM.widgets.register(PasswordConfirm);
