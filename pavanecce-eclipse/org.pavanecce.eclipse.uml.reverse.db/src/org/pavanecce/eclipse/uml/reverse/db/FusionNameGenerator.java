package org.pavanecce.eclipse.uml.reverse.db;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.datatools.connectivity.sqm.core.rte.jdbc.JDBCTable;
import org.eclipse.datatools.modelbase.sql.tables.PersistentTable;

public class FusionNameGenerator extends DefaultNameGenerator {
	@Override
	public boolean isAssociation(PersistentTable table) {
		return table.getName().toLowerCase().startsWith("asso_");
	}

	@Override
	public boolean isEnum(PersistentTable table) {
		if(table.getName().toLowerCase().startsWith("lookup_")){
			try {
				ResultSet rst = ((JDBCTable)table).getConnection().createStatement().executeQuery("select count(*) as total from " + table.getName());
				if(rst.getInt("total")<30){
					return true;
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		return false;
	}
}
