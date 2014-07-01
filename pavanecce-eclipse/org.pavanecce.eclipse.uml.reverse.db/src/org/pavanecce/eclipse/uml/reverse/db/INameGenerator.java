package org.pavanecce.eclipse.uml.reverse.db;

import org.eclipse.datatools.modelbase.sql.constraints.ForeignKey;
import org.eclipse.datatools.modelbase.sql.tables.Column;
import org.eclipse.datatools.modelbase.sql.tables.PersistentTable;

public interface INameGenerator {
	String calcAssociationEndName(PersistentTable table);

	String calcAssociationEndName(ForeignKey foreignKey);

	String calcAssociationName(ForeignKey foreignKey);

	String calcTypeName(PersistentTable returnType);

	String calcAttributeName(Column c);

	String calcPackagename(PersistentTable returnType);

	boolean isAssociation(PersistentTable table);

	boolean isEnum(PersistentTable table);
}
