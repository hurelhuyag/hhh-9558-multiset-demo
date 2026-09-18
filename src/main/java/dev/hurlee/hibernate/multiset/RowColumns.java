package dev.hurlee.hibernate.multiset;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.SelectableMapping;

/** The columns an entity contributes to a {@code ROW(...)}: identifier, discriminator, version, then the attributes. */
final class RowColumns {

    private RowColumns() {
    }

    static List<SelectableMapping> of(EntityMappingType entity) {
        List<SelectableMapping> columns = new ArrayList<>();
        // forEachSelectable covers the attributes only
        entity.getIdentifierMapping().forEachSelectable((i, selectable) -> add(columns, selectable));
        if (entity.getDiscriminatorMapping() != null && entity.getDiscriminatorMapping().hasPhysicalColumn()) {
            entity.getDiscriminatorMapping().forEachSelectable((i, selectable) -> add(columns, selectable));
        }
        if (entity.getVersionMapping() != null) {
            entity.getVersionMapping().forEachSelectable((i, selectable) -> add(columns, selectable));
        }
        entity.forEachSelectable((i, selectable) -> add(columns, selectable));
        return columns;
    }

    static void add(List<SelectableMapping> columns, SelectableMapping selectable) {
        if (selectable.isFormula()) {
            return; // not a column; formulas are not supported inside the ROW yet
        }
        for (SelectableMapping existing : columns) {
            if (existing.getSelectableName().equals(selectable.getSelectableName())) {
                return;
            }
        }
        columns.add(selectable);
    }

    static int indexOf(List<SelectableMapping> columns, String selectableName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getSelectableName().equals(selectableName)) {
                return i;
            }
        }
        return -1;
    }
}
