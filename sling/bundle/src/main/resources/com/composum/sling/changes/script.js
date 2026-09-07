// Client widgets for the standalone "Changes" capability (com.composum.sling.changes.Changes) -
// self-contained so any consuming plugin (currently only Browser) can just include the rendered
// fragments (dialogs, the navbar/badge.html embed) and this script, without wiring anything
// itself: every widget here scopes to markup Changes itself renders, and 'CPM.Dialog#open'
// already calls 'CPM.widgets.initialize()' on a freshly fetched dialog fragment, so these attach
// automatically the moment such a fragment appears in the DOM.
//
// Event contract for a consumer: after any mutation - dialog-based (Create/Delete/Move/Change
// Property, via the shared 'DialogForm', which fires the generic 'dialog:success' event with the
// '{path, pending}' JSON response) or not (Paste, Save All, Revert All - see below, which fire
// 'changes:applied' with the same shape directly) - a consumer that wants to refresh its own view
// should listen for both 'dialog:success' and 'changes:applied' and react to a numeric
// 'result.pending' field the same way.

// the Change-Property dialog's "Remove" button: not a submit button (there is nothing in the
// shared 'DialogForm' to distinguish "which button was clicked"), so it just adds a hidden
// 'remove' field and re-triggers the very same form submit 'DialogForm' already handles
class PropertyRemove extends ViewWidget {

  static selector = '.changes-property_remove';

  constructor(element) {
    super(element);
    this.$el.on('click', () => {
      const $form = this.$el.closest('form');
      $('<input type="hidden" name="remove" value="true">').appendTo($form);
      $form.trigger('submit');
    });
  }
}

CPM.widgets.register(PropertyRemove);

// the Change-Property dialog's value area (see changes/dialogs/property.html and its per-row
// changes/dialogs/propertyValue.html): one input row per value, each submitted under the same
// 'value' form field name (the server reads them all via 'getParameterValues', in row order - so
// the row order alone is what encodes a multi-value property's own order) - deliberately not a
// single shared textarea split by newline, since an individual value can itself contain newlines,
// which a shared split cannot represent unambiguously. Rows carry no per-row buttons at all - a
// radio button marks the "current" row, and a single toolbar above the list (Add plus Up/Down/
// Remove for the selected row) covers every list operation in one place. Moving a row leaves its
// radio (the same DOM element) checked, so the selection
// travels with the value - repeatedly clicking Up/Down walks the same value across the list without
// ever having to reselect it, which is the whole point over a button-per-row layout. The toolbar/
// radios are only relevant while "Multi" is checked; unchecking it collapses back to the first row
// so a non-multi property is never submitted with more than one 'value' field. The radios carry no
// 'name' attribute (grouping is enforced manually below), so a checked one is never itself
// submitted as a stray, meaningless form field.
class PropertyValues extends ViewWidget {

  static selector = '.changes-property_values';

  constructor(element) {
    super(element);
    this.$list = this.$el.find('.changes-property_value-list');
    this.$add = this.$el.find('.changes-property_value-add');
    this.$toolbar = this.$el.find('.changes-property_value-toolbar');
    this.$up = this.$el.find('.changes-property_value-up');
    this.$down = this.$el.find('.changes-property_value-down');
    this.$remove = this.$el.find('.changes-property_value-remove');
    this.$multi = this.$el.closest('form').find('input[name="multi"]');
    this.$name = this.$el.closest('form').find('input[name="name"]');
    this.suggestUri = this.$el.data('suggest-uri');
    this.primaryTypeSuggestUri = this.$el.data('primary-type-suggest-uri');
    this.mixinTypeSuggestUri = this.$el.data('mixin-type-suggest-uri');
    this.treeUri = this.$el.data('tree-uri');
    this.$add.on('click', () => this.addValue(''));
    this.$list.on('click', '.changes-property_value-select', (event) =>
      this.selectItem($(event.currentTarget).closest('.changes-property_value-item')));
    this.$up.on('click', () => this.moveSelected(-1));
    this.$down.on('click', () => this.moveSelected(1));
    this.$remove.on('click', () => this.removeSelected());
    this.$multi.on('change', () => this.updateMulti());
    this.$name.on('input', () => this.updateNameMode());
    this.updateNameMode();
    this.updateMulti();
  }

  // jcr:primaryType/jcr:mixinTypes are well-known JCR properties whose values are node type
  // names, not paths - while the Name field holds one of them, every value row's autocomplete
  // switches over to the matching type-name suggestions (see changes/dialogs/property.html's
  // 'data-primary-type-suggest-uri'/'data-mixin-type-suggest-uri', server-side
  // AbstractToolsPlugin#nodeTypeSuggestions) - kept as two disjoint candidate lists/endpoints
  // rather than one shared list, since a primary type can never be assigned as a mixin or vice
  // versa, and offering one's names while editing the other would just be confusing. The (for a
  // type name, meaningless) tree-browse button is hidden, and "Multi" defaults to what the JCR
  // spec actually requires for each (mixinTypes is always multi-valued, primaryType never is) - a
  // default, not a lock: disabling the checkbox outright would exclude it from the submitted form
  // data entirely, so the user can still override it if they really need to.
  updateNameMode() {
    const name = this.$name.val();
    let suggestUri = this.suggestUri;
    let typeMode = false;
    if (name === 'jcr:primaryType' && this.primaryTypeSuggestUri) {
      suggestUri = this.primaryTypeSuggestUri;
      typeMode = true;
    } else if (name === 'jcr:mixinTypes' && this.mixinTypeSuggestUri) {
      suggestUri = this.mixinTypeSuggestUri;
      typeMode = true;
    }
    this.getItems().find('.tools-path_input').each((i, el) => {
      // jQuery's '.data()' caches a 'data-*' attribute's value the first time it's read for an
      // element (which 'PathPicker' already did, at its own construction) and does not re-read
      // the attribute afterwards - so this must go through '.data()' too, not '.attr()', or
      // 'PathPicker#fetchSuggestions' (which reads via '.data()') would keep seeing the original
      // value no matter what the attribute itself now says
      if (suggestUri) {
        $(el).data('suggest-uri', suggestUri);
      } else {
        $(el).removeData('suggest-uri');
      }
    });
    this.$el.toggleClass('type-mode', typeMode);
    if (name === 'jcr:mixinTypes' && !this.$multi.prop('checked')) {
      this.$multi.prop('checked', true).trigger('change');
    } else if (name === 'jcr:primaryType' && this.$multi.prop('checked')) {
      this.$multi.prop('checked', false).trigger('change');
    }
  }

  getItems() {
    return this.$list.find('.changes-property_value-item');
  }

  getSelected() {
    return this.$list.find('.changes-property_value-select:checked').closest('.changes-property_value-item');
  }

  updateMulti() {
    const multi = this.$multi.prop('checked');
    // 'Add' lives inside the toolbar itself now, so hiding the toolbar already hides it too
    this.$el.toggleClass('multi-value', multi);
    this.$el.toggleClass('single-value', !multi);
    if (!multi) {
      this.getItems().slice(1).remove();
    }
    this.ensureSelection();
    this.updateToolbarState();
  }

  // marks one row as the current one - manual exclusivity since the radios share no 'name' (see
  // class comment above), and updates the highlight/toolbar state to match
  selectItem($item) {
    this.$list.find('.changes-property_value-select').prop('checked', false);
    $item.find('.changes-property_value-select').prop('checked', true);
    this.getItems().removeClass('changes-property_value-item-selected');
    $item.addClass('changes-property_value-item-selected');
    this.updateToolbarState();
  }

  // a fresh row (initial render, or one just added) has nothing selected yet - default to the
  // first one rather than leaving the toolbar permanently disabled until the user clicks a radio
  ensureSelection() {
    if (this.getSelected().length === 0) {
      const $first = this.getItems().first();
      if ($first.length > 0) {
        this.selectItem($first);
      }
    }
  }

  // disables Up on the first row, Down on the last, and Remove once only one row is left, rather
  // than leaving a boundary click a silent no-op
  updateToolbarState() {
    const $items = this.getItems();
    const index = $items.index(this.getSelected());
    this.$up.prop('disabled', index <= 0);
    this.$down.prop('disabled', index < 0 || index >= $items.length - 1);
    this.$remove.prop('disabled', $items.length <= 1);
  }

  moveSelected(direction) {
    const $item = this.getSelected();
    if ($item.length === 0) {
      return;
    }
    const $sibling = direction < 0 ? $item.prev('.changes-property_value-item') : $item.next('.changes-property_value-item');
    if ($sibling.length > 0) {
      if (direction < 0) {
        $item.insertBefore($sibling);
      } else {
        $item.insertAfter($sibling);
      }
      this.updateToolbarState();
    }
  }

  addValue(value) {
    const $item = $('<div class="input-group mb-1 changes-property_value-item">'
      + '<div class="input-group-text changes-property_value-radio">'
      + '<input type="radio" class="form-check-input mt-0 changes-property_value-select" aria-label="Select this value">'
      + '</div>'
      + '<textarea class="form-control tools-path_input" name="value" rows="2"></textarea>'
      + '<button type="button" class="btn btn-outline-secondary tools-tree_picker-btn" tabindex="-1" title="Browse...">'
      + '<i class="bi bi-folder2-open"></i></button></div>');
    const $textarea = $item.find('textarea').val(value);
    if (this.suggestUri) {
      // set programmatically (not string-concatenated into the markup above) so the widget below
      // never has to worry about escaping a value it didn't render itself
      $textarea.attr('data-suggest-uri', this.suggestUri);
    }
    if (this.treeUri) {
      $item.find('.tools-tree_picker-btn').attr('data-tree-uri', this.treeUri);
    }
    this.$list.append($item);
    // this row's own path-picker only attaches on initialize, since it never went through the
    // server-rendered template the other rows came from
    CPM.widgets.initialize($item);
    // corrects the suggest-uri/browse-button just set above if the Name field currently holds
    // jcr:primaryType/jcr:mixinTypes - simpler than duplicating that decision here too
    this.updateNameMode();
    this.updateMulti();
    // the newly added row is the one most likely to be edited/reordered next
    this.selectItem($item);
  }

  removeSelected() {
    const $item = this.getSelected();
    if ($item.length > 0 && this.getItems().length > 1) {
      $item.remove();
      this.ensureSelection();
      this.updateToolbarState();
    }
  }
}

CPM.widgets.register(PropertyValues);

// the Move dialog's "Also adjust path references..." checkbox (see changes/dialogs/move.html) -
// only shown while a ReferencesService is bound (server-side gated via 'references.available');
// reveals the "Search Root" field alongside it while checked, so the field never confuses someone
// who isn't asking for the (potentially repository-wide) reference search at all
class MoveReferencesToggle extends ViewWidget {

  static selector = '.changes-move_adjust-toggle';

  constructor(element) {
    super(element);
    this.$root = this.$el.closest('form').find('.changes-move_references-root');
    this.$el.on('change', () => this.$root.toggleClass('d-none', !this.$el.prop('checked')));
  }
}

CPM.widgets.register(MoveReferencesToggle);

// the pending-changes panel's Save All / Revert All buttons - both carry their own POST url in
// 'data-url', so one generic widget (matching both classes via a compound selector) covers both
class PendingAction extends ViewWidget {

  static selector = '.changes-pending_commit, .changes-pending_discard';

  constructor(element) {
    super(element);
    this.$el.on('click', () => {
      $.ajax({
        type: 'POST',
        url: this.$el.data('url'),
        success: (result) => $(document).trigger('changes:applied', [result]),
        error: (jqXHR) => alert((jqXHR.responseJSON && jqXHR.responseJSON.message) || 'Request failed.'),
        async: true,
        cache: false
      });
    });
  }
}

CPM.widgets.register(PendingAction);

// the embeddable "Pending Changes" badge (see navbar/badge.html) - opens the pending-changes
// panel on click, and keeps its own count/coloring in sync with every mutation (dialog-based or
// not) by listening to both halves of the event contract described above. Always visible (the
// icon/badge color itself - 'danger' red vs. 'secondary' gray - is the "any changes pending?"
// indicator, not d-none-based show/hide), so this must mirror the exact same
// 'text-danger'/'bg-danger' vs. 'text-secondary'/'bg-secondary' choice navbar/badge.html itself
// makes server-side from 'changes.pendingVisible', or the two would drift out of sync the moment
// anything changes without a full page reload.
class PendingBadge extends ViewWidget {

  static selector = '.changes-pending-group';

  constructor(element) {
    super(element);
    this.pendingUri = this.$el.data('pending-uri');
    this.$icon = this.$el.find('.changes-pending_toggle i');
    this.$badge = this.$el.find('.changes-pending_count');
    this.$el.find('.changes-pending_toggle').on('click', (event) => {
      event.preventDefault();
      new CPM.Dialog(this.pendingUri).open();
    });
    $(document)
      .on('dialog:success', (event, el, result) => this.onResult(result))
      .on('changes:applied', (event, result) => this.onResult(result));
  }

  onResult(result) {
    if (result && typeof result.pending === 'number') {
      this.updateBadge(result.pending);
    }
  }

  updateBadge(pending) {
    const active = pending > 0;
    this.$icon.toggleClass('text-danger', active).toggleClass('text-secondary', !active);
    this.$badge.toggleClass('bg-danger', active).toggleClass('bg-secondary', !active).text(pending);
  }
}

CPM.widgets.register(PendingBadge);
