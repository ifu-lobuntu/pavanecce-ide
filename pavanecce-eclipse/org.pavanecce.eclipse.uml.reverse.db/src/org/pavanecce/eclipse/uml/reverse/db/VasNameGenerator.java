package org.pavanecce.eclipse.uml.reverse.db;

import org.eclipse.datatools.modelbase.sql.tables.Column;
import org.eclipse.datatools.modelbase.sql.tables.PersistentTable;
import org.pavanecce.common.util.NameConverter;

public class VasNameGenerator extends DefaultNameGenerator implements INameGenerator {
	@Override
	protected String calcTypeName(String tableName) {
		String rawName = tableName.split("\\_")[1];
		return NameConverter.capitalize(NameConverter.underscoredToCamelCase(rawName));
	}

	@Override
	public String calcPackagename(PersistentTable returnType) {
		return returnType.getName().split("\\_")[0];
	}
	@Override
	public boolean isInteresting(PersistentTable table) {
		return table.getName().toLowerCase().startsWith("valueaccounting");
	}
	@Override
	public boolean isInteresting(Column column) {
		return !column.getName().equalsIgnoreCase("id");
	}
}
