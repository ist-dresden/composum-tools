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

  // re-fetches just the currently selected node's own children from the server (jsTree's
  // 'refresh_node' re-runs the 'data' callback for that one node) - narrower than 'onRefresh'
  // above, which refreshes the whole tree; a manual "Reload" action has no mutation result
  // telling it what changed, so it only ever makes sense to reload the one node the user is
  // looking at (mirrors Browser's own BrowserTree#reloadSelected)
  reloadSelected() {
    const node = this.getSelectedNode();
    if (node) {
      this.jstree.refresh_node(node);
    }
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

// Reload button in the tree-bar, next to the (write-gated) "Changes" dropdown - re-fetches just
// the currently selected tree node's own children (see UsersTree#reloadSelected), useful whenever
// the repository changed by some other means the User Manager has no way to know about on its
// own. Kept as its own tiny widget rather than folded into UsersChangeMenu, since that one's
// markup only exists at all while users.writeEnabled - Reload is a read-only convenience and must
// always be available regardless (mirrors Browser's own BrowserNodeToolbar/BrowserTree split for
// the identical reason).
class UsersTreeReload extends ViewWidget {

  static selector = '.usermgr-page_tree-reload';

  constructor(element) {
    super(element);
    this.$el.on('click', (event) => {
      event.preventDefault();
      const tree = Widgets.getView(UsersTree.selector, UsersTree);
      if (tree) {
        tree.reloadSelected();
      }
    });
  }
}

CPM.widgets.register(UsersTreeReload);

// the permanently visible search bar in the right column (a sibling of '.usermgr-page_detail-panel',
// not nested inside it - see '.usermgr-page_right-panel' in style.css for why), with a permanent
// (not just capped) ~45% height - always reserved, whether or not a search is currently active.
// Two independent, combinable patterns, each backed by its own indexed lookup: a name/principal
// wildcard pattern (UserManager#query -> JcrAuthorizableOperations#find) and an affected-path
// wildcard pattern (-> JcrAuthorizableOperations#findByAffectedPath) - matching the legacy Nodes
// tool's "Authorizable Name"/"Affected Path" search fields, minus its third "Graph" mode and its
// separate "Authorizable Path" field, neither of which was asked for. Results render as a table
// (icon/label/path columns), matching the legacy tool's own result layout - and, unlike a
// transient dropdown, the table stays in place after a click so several matches can be tried in
// turn, each loading its own detail below. Selecting a result reuses the same 'path:select' event
// a folder-entry click already uses, so the tree drills open and the detail panel loads exactly
// like any other navigation.
class UsersSearch extends ViewWidget {

  static selector = '.usermgr-page_search';

  static minLength = 2;

  static debounceMillis = 250;

  constructor(element) {
    super(element);
    this.queryUrl = this.$el.data('query-url');
    this.browserUri = this.$el.data('browser-uri');
    this.$nameInput = this.$el.find('.usermgr-page_search-input');
    this.$pathInput = this.$el.find('.usermgr-page_search-path-input');
    this.$results = this.$el.find('.usermgr-page_search-results');
    this.$nameInput.on('input', this.onInput.bind(this));
    this.$pathInput.on('input', this.onInput.bind(this));
    this.showHint('Enter a name and/or an affected-path pattern above (wildcards \'*\'/\'?\' allowed).');
  }

  onInput() {
    const text = this.$nameInput.val().trim();
    const path = this.$pathInput.val().trim();
    if (this.timer) {
      clearTimeout(this.timer);
    }
    if (text.length < UsersSearch.minLength && path.length < UsersSearch.minLength) {
      this.showHint('Enter a name and/or an affected-path pattern above (wildcards \'*\'/\'?\' allowed).');
      return;
    }
    this.timer = setTimeout(() => this.search(text, path), UsersSearch.debounceMillis);
  }

  search(text, path) {
    const params = [];
    if (text) {
      params.push('text=' + encodeURIComponent(text));
    }
    if (path) {
      params.push('path=' + encodeURIComponent(path));
    }
    $.ajax({
      type: 'GET',
      url: this.queryUrl + '?' + params.join('&'),
      success: (results) => this.showResults(results),
      async: true,
      cache: false
    });
  }

  // a Principal/Path/Rule table - same shape and same per-cell behavior as the "Affected Paths"
  // tab's own server-rendered table (details/affectedPathsTable.html), just built client-side
  // from the AffectedPathEntry JSON UserManager#query now returns in every mode (see its own
  // Javadoc for what each mode actually computes)
  showResults(results) {
    if (!results || results.length === 0) {
      this.showHint('No matches.');
      return;
    }
    // same exact classes as the "Affected Paths" tab's own table (details/affectedPathsTable.html)
    // - 'table-striped' included, so both look identical, not just structurally similar
    const $table = $('<table class="table table-sm table-striped usermgr-page_affected-paths"></table>');
    const $thead = $('<thead><tr><th></th><th>Principal</th><th>Path</th><th>Rule</th></tr></thead>').appendTo($table);
    const $tbody = $('<tbody></tbody>').appendTo($table);
    results.forEach((entry) => {
      const $principal = $('<a href="#" class="usermgr-page_folder-entry-link"></a>').text(entry.principal).on('click', (event) => {
        event.preventDefault();
        // deliberately does not clear the inputs or hide the results - the table stays put so
        // several matches can be tried in turn, each loading its own detail below
        $(document).trigger('path:select', [entry.principalPath]);
      });
      const $path = $('<a></a>').attr('href', this.browserUri + entry.path).text(entry.path);
      $('<tr></tr>')
        .append($('<td class="icon"></td>').append($('<i></i>').addClass('bi bi-' + entry.principalIcon)))
        .append($('<td class="principal"></td>').append($principal))
        .append($('<td class="path"></td>').append($path))
        .append($('<td class="rule"></td>').addClass('type-' + entry.type).text(entry.type + ': ' + entry.privileges))
        .appendTo($tbody);
    });
    this.$results.empty().append($table);
  }

  showHint(text) {
    this.$results.empty().append($('<div class="usermgr-page_search-empty"></div>').text(text));
  }
}

CPM.widgets.register(UsersSearch);

// The tree-bar's "Changes" dropdown - Create User/System User/Group (no selection needed) plus
// Delete (moved here from the Principal tab's own action toolbar - see UserManager#actions,
// which no longer lists it), shown only when writeEnabled (see page.html). Tracks the currently
// selected authorizable's path via the same 'authorizable:selected' event UsersDetail itself
// listens to, exactly like the Browser's own node-toolbar dropdown (script.js#BrowserNodeToolbar)
// tracks 'path:selected' for its own Delete/Move/Copy/Paste actions - Delete's own server route
// already rejects a path that isn't a real, deletable authorizable (not selected yet, a folder,
// or a protected id like 'admin'/'anonymous'), so no client-side pre-filtering is needed here.
class UsersChangeMenu extends ViewWidget {

  static selector = '.usermgr-page_change-menu';

  constructor(element) {
    super(element);
    this.dialogUrl = this.$el.data('dialog-url');
    $(document).on('authorizable:selected', this.onAuthorizableSelected.bind(this));
    ['createUser', 'createSystemUser', 'createGroup'].forEach((key) => {
      this.$el.find('.usermgr-page_action-' + key).on('click',
        () => new CPM.Dialog(this.dialogUrl + key + '.html').open());
    });
    this.$el.find('.usermgr-page_action-delete').on('click', (event) => {
      event.preventDefault();
      if (this.path) {
        new CPM.Dialog(this.dialogUrl + 'delete.html' + this.path).open();
      }
    });
  }

  onAuthorizableSelected(event, path) {
    this.path = path;
  }
}

CPM.widgets.register(UsersChangeMenu);

// a link that selects a given authorizable path (fires the shared 'path:select' event, same as
// clicking the tree) - used by the Groups/Members tab entries and the Affected Paths tab's
// Principal column alike. A plain widget rather than ad-hoc wiring inside UsersDetail's own
// onContentLoaded, specifically so it also works for the Affected Paths tab's lazily-loaded
// content: CPM.widgets.initialize() re-scans that content on load (see LazyTabPane in
// tools/script.js), but UsersDetail's one-time onContentLoaded wiring never revisits it.
class FolderEntryLink extends ViewWidget {

  static selector = '.usermgr-page_folder-entry-link';

  constructor(element) {
    super(element);
    this.$el.on('click', (event) => {
      event.preventDefault();
      const path = this.$el.data('path');
      if (path) {
        $(document).trigger('path:select', [path]);
      }
    });
  }
}

CPM.widgets.register(FolderEntryLink);

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
    ['enable', 'disable', 'password', 'changeProfile', 'delete', 'addToGroup', 'addMember'].forEach((key) => {
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
    // the reload icon at the far right of the header strip (details/reloadAction.html), matching
    // the Browser's own always-available reload action - re-fetches the whole detail view rather
    // than just the currently active tab (unlike Browser, our tabs aren't uniformly lazy-loaded,
    // so there is no single "active tab's own content" to refetch in isolation) - this still
    // correctly refreshes whichever tab is showing, including re-triggering the Affected Paths
    // tab's own lazy load if that happens to be the active one, since 'ResumingTabs' restores it
    // and 'LazyTabPane' fires fresh against the newly rendered (unloaded) tab-pane
    this.$el.find('.usermgr-page_detail-reload').on('click', (event) => {
      event.preventDefault();
      this.load();
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

// Switches which per-tab action-group is visible in the detail panel's action toolbar to match
// whichever tab is currently active - e.g. Enable/Disable/Change Password/Delete for Principal,
// "Add to Group" for Groups, "Add Member" for Members (see details/toolbar.html,
// details/addToGroupAction.html, details/addMemberAction.html - each renders one
// '.usermgr-page_detail-actions_group[data-tab="..."]' block, all siblings inside the same
// '.usermgr-page_detail-actions' container). A tab with no matching group (Affected Paths) simply
// shows none. Deliberately not folded into 'ResumingTabs' (its own 'onShownCallback' constructor
// argument isn't reachable through the generic, no-extra-args widget auto-registration path) -
// a small, independent listener on the same 'shown.bs.tab' event is simpler here.
class TabActionSwitcher extends ViewWidget {

  static selector = '.usermgr-page_detail-panel-header';

  constructor(element) {
    super(element);
    this.$('.usermgr-page_detail-tabs a[data-bs-toggle="tab"]').on('shown.bs.tab', this.onTabShown.bind(this));
  }

  onTabShown(event) {
    const tabId = $(event.target).attr('aria-controls');
    this.$('.usermgr-page_detail-actions_group').each(function () {
      $(this).toggleClass('d-none', $(this).data('tab') !== tabId);
    });
  }
}

CPM.widgets.register(TabActionSwitcher);

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

// the "Change Profile" dialog's dynamic name/value row editor - a generic, no-fixed-schema
// property list for a user's 'profile' child node (matching the legacy Composum Nodes tool's own
// approach), see UserManager#changeProfileDialog/#changeProfile. Each row submits as a plain
// 'name'/'value' pair - parallel, repeated request parameters (same convention the Changes
// plugin's own multi-value property editor uses), so no client-side indexing/renumbering is
// needed when a row is added or removed.
class ProfileEditor extends ViewWidget {

  static selector = '.usermgr-page_profile-list';

  constructor(element) {
    super(element);
    this.$el.closest('.tools-dialog_form').find('.usermgr-page_profile-add')
      .on('click', this.addRow.bind(this));
    this.$el.on('click', '.usermgr-page_profile-remove', this.removeRow.bind(this));
  }

  addRow() {
    $('<div class="input-group mb-1 usermgr-page_profile-item">')
      .append('<input type="text" class="form-control usermgr-page_profile-name" name="name" placeholder="Property name">')
      .append('<input type="text" class="form-control usermgr-page_profile-value" name="value" placeholder="Value">')
      .append('<button type="button" class="btn btn-outline-secondary usermgr-page_profile-remove" tabindex="-1" title="Remove"><i class="bi bi-x-lg"></i></button>')
      .appendTo(this.$el)
      .find('.usermgr-page_profile-name').trigger('focus');
  }

  removeRow(event) {
    $(event.currentTarget).closest('.usermgr-page_profile-item').remove();
  }
}

CPM.widgets.register(ProfileEditor);
