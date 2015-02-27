package org.pavanecce.eclipse.uml.reverse.db;

import static org.pavanecce.uml.common.util.TagNames.INDICES;
import static org.pavanecce.uml.common.util.TagNames.IS_ASCENDING;
import static org.pavanecce.uml.common.util.TagNames.IS_UNIQUE;
import static org.pavanecce.uml.common.util.TagNames.LINKED_PROPERTIES;
import static org.pavanecce.uml.common.util.TagNames.LINK_PERSISTENT_NAME;
import static org.pavanecce.uml.common.util.TagNames.NAME;
import static org.pavanecce.uml.common.util.TagNames.PROPERTIES;
import static org.pavanecce.uml.common.util.TagNames.SOURCE_PERSISTENT_NAME;
import static org.pavanecce.uml.common.util.TagNames.TARGET_PROPERTY;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.datatools.connectivity.sqm.core.rte.jdbc.JDBCTable;
import org.eclipse.datatools.modelbase.sql.constraints.ForeignKey;
import org.eclipse.datatools.modelbase.sql.constraints.Index;
import org.eclipse.datatools.modelbase.sql.constraints.IndexMember;
import org.eclipse.datatools.modelbase.sql.constraints.UniqueConstraint;
import org.eclipse.datatools.modelbase.sql.tables.BaseTable;
import org.eclipse.datatools.modelbase.sql.tables.Column;
import org.eclipse.datatools.modelbase.sql.tables.PersistentTable;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.Association;
import org.eclipse.uml2.uml.AssociationClass;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Classifier;
import org.eclipse.uml2.uml.DataType;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Enumeration;
import org.eclipse.uml2.uml.EnumerationLiteral;
import org.eclipse.uml2.uml.LiteralBoolean;
import org.eclipse.uml2.uml.LiteralInteger;
import org.eclipse.uml2.uml.LiteralReal;
import org.eclipse.uml2.uml.LiteralString;
import org.eclipse.uml2.uml.LiteralUnlimitedNatural;
import org.eclipse.uml2.uml.Model;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Property;
import org.eclipse.uml2.uml.Slot;
import org.eclipse.uml2.uml.Type;
import org.eclipse.uml2.uml.UMLFactory;
import org.eclipse.uml2.uml.UMLPackage;
import org.eclipse.uml2.uml.ValueSpecification;
import org.pavanecce.uml.common.util.EmfClassifierUtil;
import org.pavanecce.uml.common.util.EmfPropertyUtil;
import org.pavanecce.uml.common.util.EmfValueSpecificationUtil;
import org.pavanecce.uml.common.util.LibraryImporter;
import org.pavanecce.uml.common.util.PersistentNameUtil;
import org.pavanecce.uml.common.util.ProfileApplier;
import org.pavanecce.uml.common.util.StereotypeNames;
import org.pavanecce.uml.common.util.UmlResourceSetFactory;

@SuppressWarnings("unchecked")
public class UmlGenerator {

	private ClassifierFactory factory;
	private INameGenerator nameGenerator = new FusionNameGenerator();
	private Collection<Classifier> pkPopulatedClasses = new HashSet<Classifier>();
	private Set<Element> databaseElements = new HashSet<Element>();

	public void generateUml(Collection<PersistentTable> selection, Model library, IProgressMonitor pm) {
		init(library);
		registerAffectedElements(selection);
		importTables(selection, library,pm);
		removeObsoleteElements();
	}

	private void importTables(Collection<PersistentTable> selection, Model library, IProgressMonitor pm) {
		pm.beginTask("Importing Tables", selection.size());
		for (PersistentTable t : selection) {
			if (nameGenerator.isInteresting(t)) {
				Classifier classifier = factory.getClassifierFor(t);
				if (classifier instanceof AssociationClass) {
					AssociationClass cls = (AssociationClass) classifier;
					ensurePrimaryKeyPopulated(t, cls);
					Collection<ForeignKey> uniqueForeignKeys = populateAssociationEnds(t, cls);
					List<ForeignKey> otherForeignKeys = t.getForeignKeys();
					otherForeignKeys.removeAll(uniqueForeignKeys);
					for (ForeignKey foreignKey : otherForeignKeys) {
						Association ass = findOrCreateAssociation(cls, foreignKey);
						Property toEnd = populateToEnd(foreignKey, ass);
						populateFromEnd(foreignKey, ass, toEnd);
					}
					populateAttributes(library, classifier, t);
					populateIndices(cls, t);
				} else if (classifier instanceof Association) {
					populateIndices(classifier, t);
					populateAssociationEnds(t, (Association) classifier);
				} else if (classifier instanceof Class) {
					Class cls = (Class) classifier;
					ensurePrimaryKeyPopulated(t, cls);
					populateAttributes(library, classifier, t);
					populateAssociations(library, cls, t);
					populateIndices(cls, t);
				} else if (classifier instanceof Enumeration) {
					Enumeration cls = (Enumeration) classifier;
					ensurePrimaryKeyPopulated(t, cls);
					populateAttributes(library, classifier, t);
					populateAssociations(library, cls, t);
					populateIndices(cls, t);
					populateEnumLiterals(t, cls);
				}
			}
			pm.worked(1);
		}
		pm.done();
	}

	private void populateEnumLiterals(PersistentTable t, Enumeration cls) {
		try {
			ResultSet rst = ((JDBCTable) t).getConnection().createStatement().executeQuery("select * from " + t.getName());
			while (rst.next()) {
				EList<Property> ownedAttributes = cls.getOwnedAttributes();
				Property nameAttribute = null;
				for (Property property : ownedAttributes) {
					if (property.getName().equalsIgnoreCase("code")) {
						nameAttribute = property;
						break;
					}
				}
				if (nameAttribute == null) {
					for (Property property : ownedAttributes) {
						if (property.getName().equalsIgnoreCase("name")) {
							nameAttribute = property;
							break;
						}
					}
				}
				if (nameAttribute == null) {
					for (Property property : ownedAttributes) {
						if (property.getName().endsWith("Name")) {
							nameAttribute = property;
							break;
						}
					}
				}
				if (nameAttribute == null) {
					System.out.println();
				}
				String literalName = rst.getString(PersistentNameUtil.getPersistentName(nameAttribute));
				if (literalName != null && !literalName.trim().isEmpty()) {
					EnumerationLiteral ownedLiteral = cls.getOwnedLiteral(literalName, false, true);
					for (Property property : ownedAttributes) {
						if (property.getType() instanceof DataType) {
							Slot slot = EmfValueSpecificationUtil.getSlotForFeature(ownedLiteral, property);
							if (slot == null) {
								slot = ownedLiteral.createSlot();
								slot.setDefiningFeature(property);
							}
							slot.getValues().clear();
							ValueSpecification newValue = null;
							if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "String")) {
								LiteralString value = UMLFactory.eINSTANCE.createLiteralString();
								value.setName(property.getName());
								value.setValue(rst.getString(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							} else if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "Integer")) {
								LiteralInteger value = UMLFactory.eINSTANCE.createLiteralInteger();
								value.setName(property.getName());
								value.setValue(rst.getInt(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							} else if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "Real")) {
								LiteralReal value = UMLFactory.eINSTANCE.createLiteralReal();
								value.setName(property.getName());
								value.setValue(rst.getDouble(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							} else if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "Boolean")) {
								LiteralBoolean value = UMLFactory.eINSTANCE.createLiteralBoolean();
								value.setName(property.getName());
								value.setValue(rst.getBoolean(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							} else if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "DateTime")) {
								LiteralString value = UMLFactory.eINSTANCE.createLiteralString();
								value.setName(property.getName());
								value.setValue(rst.getString(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							} else if (EmfClassifierUtil.comformsToLibraryType(property.getType(), "Time")) {
								LiteralString value = UMLFactory.eINSTANCE.createLiteralString();
								value.setName(property.getName());
								value.setValue(rst.getString(PersistentNameUtil.getPersistentName(property)));
								newValue = value;
							}else{
								EmfClassifierUtil.comformsToLibraryType(property.getType(), "Time");
								System.out.println(property.getType());
							}
							if(newValue!=null){
								slot.getValues().add(newValue);
							}
						}
					}
				}
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	private Collection<ForeignKey> populateAssociationEnds(PersistentTable t, Association ass) {
		List<UniqueConstraint> uniqueConstraints = t.getUniqueConstraints();
		for (UniqueConstraint uc : uniqueConstraints) {
			EList<Column> uniqueColumns = uc.getMembers();
			Set<ForeignKey> foreignKeys = new HashSet<ForeignKey>();
			for (Column column : uniqueColumns) {
				Collection<? extends ForeignKey> fksForColumn = getForeignKeys(t, column);
				foreignKeys.addAll(fksForColumn);
			}
			if (foreignKeys.size() == 2) {
				for (ForeignKey fk : foreignKeys) {
					populateToEnd(fk, ass).setUpper(LiteralUnlimitedNatural.UNLIMITED);
				}
				return foreignKeys;
			}
		}
		EList<Column> uniqueColumns = t.getColumns();
		Set<ForeignKey> foreignKeys = new HashSet<ForeignKey>();
		Collection<ForeignKey> firstTwoForeignKeys = new ArrayList<ForeignKey>();
		for (Column column : uniqueColumns) {
			Collection<? extends ForeignKey> fksForColumn = getForeignKeys(t, column);
			if (fksForColumn.size() > 0 && firstTwoForeignKeys.size() < 2) {
				firstTwoForeignKeys.add(fksForColumn.iterator().next());
			}
			foreignKeys.addAll(fksForColumn);
		}

		// if we're here, the FK's did not fall in a unique constraint. Just
		// take the first two.
		for (ForeignKey fk : firstTwoForeignKeys) {
			populateToEnd(fk, ass).setUpper(LiteralUnlimitedNatural.UNLIMITED);
		}
		return firstTwoForeignKeys;

	}

	private Collection<? extends ForeignKey> getForeignKeys(PersistentTable t, Column column) {
		List<ForeignKey> foreignKeys = t.getForeignKeys();
		Collection<ForeignKey> result = new HashSet<ForeignKey>();
		for (ForeignKey fk : foreignKeys) {
			if (fk.getMembers().contains(column)) {
				result.add(fk);
			}
		}
		return result;
	}

	private void init(Model library) {
		ProfileApplier.applyProfile(library, UmlResourceSetFactory.VDFP_MAPPING_PROFILE);
		LibraryImporter.importLibraryIfNecessary(library, StereotypeNames.STANDARD_SIMPLE_TYPES);
		EcoreUtil.resolveAll(library);
		factory = new ClassifierFactory(library, nameGenerator);
	}

	@SuppressWarnings("rawtypes")
	private void removeObsoleteElements() {
		for (Element e : this.databaseElements) {
			if (e.eContainer() != null && e.eResource() != null && e.eContainingFeature() != null) {
				Object eGet = e.eContainer().eGet(e.eContainingFeature());
				if (eGet instanceof EList) {
					((EList) eGet).remove(e);
				} else {
					// No instance of this yet
				}
			}
		}
	}

	private void registerAffectedElements(Collection<PersistentTable> selection) {
		for (PersistentTable pt : selection) {
			if (nameGenerator.isInteresting(pt)) {
				Classifier c = factory.getClassifierFor(pt);
				addDependentDatabaseElements(c);
				EList<Property> ownedAttributes = getOwnedAttributes(c);
				addDataAttributes(ownedAttributes);
				Property pk = EmfPropertyUtil.getPrimaryKey(c);
				if (pk != null && pk.getType().eClass().equals(UMLPackage.eINSTANCE.getDataType())) {
					DataType pkType = (DataType) pk.getType();
					EList<Property> ownedAttributes2 = pkType.getOwnedAttributes();
					addDataAttributes(ownedAttributes2);
					addDependentDatabaseElements(pkType);
				}
			}
		}
	}

	protected EList<Property> getOwnedAttributes(Classifier c) {
		return c instanceof Class ? ((Class) c).getOwnedAttributes() : new BasicEList<Property>();
	}

	private void addDataAttributes(EList<Property> props) {
		for (Property property : props) {
			if (isDataAttribute(property)) {
				databaseElements.add(property);
			}
		}
	}

	private boolean isDataAttribute(Property property) {
		return !(property.isStatic() || property.isDerived() || property.isReadOnly());
	}

	private void addDependentDatabaseElements(Classifier c) {
		for (Association association : c.getAssociations()) {
			for (Property property : association.getMemberEnds()) {
				if (property.getOtherEnd().getType().equals(c) && !EmfPropertyUtil.isMany(property)) {
					if (!association.isDerived() && isDataAttribute(property)) {
						databaseElements.add(association);
						databaseElements.addAll(association.getMemberEnds());
					}
				}
			}
		}
	}

	private void populateIndices(Classifier cls, PersistentTable t) {
		for (EObject sa : cls.getStereotypeApplications()) {
			EStructuralFeature indicesFeature = sa.eClass().getEStructuralFeature(INDICES);
			if (indicesFeature != null) {
				EList<EObject> value = (EList<EObject>) sa.eGet(indicesFeature);
				EClass indexType = (EClass) indicesFeature.getEType();
				value.clear();
				EList<Index> index = t.getIndex();
				for (Index idx : index) {
					EObject eIndex = EcoreUtil.create(indexType);
					value.add(eIndex);
					eIndex.eSet(indexType.getEStructuralFeature(IS_ASCENDING), true);
					eIndex.eSet(indexType.getEStructuralFeature(IS_UNIQUE), idx.isUnique());
					eIndex.eSet(indexType.getEStructuralFeature(NAME), idx.getName());
					addIndexProperties(cls, idx, eIndex);
				}
			}
		}
	}

	private void addIndexProperties(Classifier cls, Index idx, EObject eIndex) {
		EList<Property> indexProperties = (EList<Property>) eIndex.eGet(eIndex.eClass().getEStructuralFeature(PROPERTIES));
		indexProperties.clear();
		List<? extends IndexMember> columns = idx.getMembers();
		ForeignKey currentFk = null;
		Set<Column> usedColumns = new HashSet<Column>();
		// try to keep the sequence as close as possible
		// is this unrealistic? maybe just store the column names
		// but we would like to keep it tight
		// what about linkedProperties' sourcePersistentName?
		for (IndexMember im : columns) {
			if (usedColumns.contains(im.getColumn())) {
				// may have been flushed with a previously matched
				// foreign key - danger: could change the sequence of
				// the columns
			} else {
				// do we need to flushed the previous foreingn key?
				currentFk = maybeFlushForeignKey(cls, indexProperties, currentFk, usedColumns, im);
				if (im.getColumn().isPartOfForeignKey()) {
					BaseTable baseTable = (BaseTable) im.getColumn().getTable();
					Collection<? extends ForeignKey> foreignKeys = baseTable.getForeignKeys();
					for (ForeignKey fk : foreignKeys) {
						if (fk.getMembers().contains(im.getColumn())) {
							boolean isInIndex = true;
							for (IndexMember curIm : columns) {
								if (!fk.getMembers().contains(curIm.getColumn())) {
									isInIndex = false;
									break;
								}
							}
							if (isInIndex) {
								currentFk = fk;
								// do with next go
								break;
							}
						}
					}
				} else {
					Property match = findAttribute(im.getColumn(), getOwnedAttributes(cls));
					if (match == null) {
					} else {
						indexProperties.add(match);
					}
					usedColumns.add(im.getColumn());
				}
			}
		}
		currentFk = maybeFlushForeignKey(cls, indexProperties, currentFk, usedColumns, null);
	}

	private ForeignKey maybeFlushForeignKey(Classifier cls, EList<Property> indexProperties, ForeignKey currentFk, Set<Column> usedColumns, IndexMember im) {
		if (currentFk != null && (im == null || !currentFk.getMembers().contains(im.getColumn()))) {
			Property match = findAssociationEnd(currentFk, EmfPropertyUtil.getEffectiveProperties(cls));
			if (match != null) {// could be associationClass end
				indexProperties.add(match);
			}
			usedColumns.addAll(currentFk.getMembers());
			currentFk = null;
		}
		return currentFk;
	}

	private void populatePrimaryKeys(PersistentTable table, Classifier classifier) {
		if (table.getPrimaryKey() != null) {
			Property primaryKey = EmfPropertyUtil.getPrimaryKey(classifier);
			if (table.getPrimaryKey().getMembers().size() == 1) {
				Column pkColumn = (Column) table.getPrimaryKey().getMembers().get(0);
				if (!EmfColumnUtil.isIdColumn(table, pkColumn)) {
					// Id primaryKeys are implied
					String attributeName = nameGenerator.calcAttributeName(pkColumn);
					if (primaryKey == null) {
						primaryKey = EmfColumnUtil.findAttribute(pkColumn, EmfPropertyUtil.getEffectiveProperties(classifier), attributeName);
					}
					if (primaryKey == null) {
						primaryKey = getOwnedAttribute(classifier, attributeName, factory.getDataTypeFor(pkColumn), false, UMLPackage.eINSTANCE.getProperty(),
								true);
					}
					if (factory.getAttributeStereotype() != null) {
						if (!primaryKey.isStereotypeApplied(factory.getAttributeStereotype())) {
							primaryKey.applyStereotype(factory.getAttributeStereotype());
						}
						primaryKey.setValue(factory.getAttributeStereotype(), "persistentName", pkColumn.getName());
					}
					databaseElements.remove(primaryKey);
				}
			} else if (classifier instanceof Class) {
				DataType pkDataType = (DataType) ((Class) classifier).getNestedClassifier(classifier.getName() + "PK", false,
						UMLPackage.eINSTANCE.getDataType(), true);
				List<ForeignKey> foreignKeys = table.getForeignKeys();
				List<Column> pkColums = table.getPrimaryKey().getMembers();
				for (ForeignKey foreignKey : foreignKeys) {
					if (nameGenerator.isInteresting(foreignKey) && nameGenerator.isInteresting(getReferencedTable(foreignKey))) {
						EList<Column> foreignKeyMembers = foreignKey.getMembers();
						if (pkColums.containsAll(foreignKeyMembers)) {
							BaseTable referencedTable = getReferencedTable(foreignKey);
							primaryKey = pkDataType.getOwnedAttribute(nameGenerator.calcAssociationEndName((PersistentTable) referencedTable),
									factory.getClassifierFor((PersistentTable) referencedTable), false, UMLPackage.eINSTANCE.getProperty(), true);
						}
					}
				}
				for (Column column : pkColums) {
					String attributeName = nameGenerator.calcAttributeName(column);
					if (!column.isPartOfForeignKey()) {
						primaryKey = pkDataType.getOwnedAttribute(attributeName, factory.getDataTypeFor(column), false, UMLPackage.eINSTANCE.getProperty(),
								true);
					}
				}
				getOwnedAttribute(classifier, "primaryKey", pkDataType, false, UMLPackage.eINSTANCE.getProperty(), true);
				/**
				 * 1. Create datatype as nested classifier <br>
				 * 2. Determine foreign keys fully available in primary key <br>
				 * 3. Create associationEnds for foreign keys <br>
				 * 4. create attributes for remaining columns<br>
				 * 5. Remember associationTables should not get here
				 */

			} else {
				throw new RuntimeException("Composite Primary Keys not supported for Enumerations");

			}
			if (classifier instanceof Enumeration) {
				if (factory.getEnumerationStereotype() != null) {
					classifier.setValue(factory.getEnumerationStereotype(), "primaryKey", primaryKey);
				}
			} else if (classifier instanceof Class) {
				if (factory.getEntityStereotype() != null) {
					classifier.setValue(factory.getEntityStereotype(), "primaryKey", primaryKey);
				}
			}
		}
		List<? extends ForeignKey> foreignKeys = table.getForeignKeys();
		for (ForeignKey foreignKey : foreignKeys) {
			PersistentTable referencedTable = getReferencedTable(foreignKey);
			ensurePrimaryKeyPopulated(referencedTable);
		}

	}

	private Property getOwnedAttribute(Classifier classifier, String string, Type pkDataType, boolean b, EClass property, boolean c) {
		if (classifier instanceof Class) {
			return ((Class) classifier).getOwnedAttribute(string, pkDataType, b, property, c);
		} else if (classifier instanceof DataType) {
			return ((DataType) classifier).getOwnedAttribute(string, pkDataType, b, property, c);
		}
		return null;

	}

	private Classifier ensurePrimaryKeyPopulated(PersistentTable referencedTable) {
		Classifier classifierFor = factory.getClassifierFor(referencedTable);
		if (classifierFor instanceof Class) {
			ensurePrimaryKeyPopulated(referencedTable, (Class) classifierFor);
		}
		return classifierFor;
	}

	protected void ensurePrimaryKeyPopulated(PersistentTable referencedTable, Classifier classifierFor) {
		databaseElements.remove(classifierFor);
		if (!pkPopulatedClasses.contains(classifierFor)) {
			pkPopulatedClasses.add(classifierFor);
			populatePrimaryKeys(referencedTable, classifierFor);
		}
	}

	private void populateAttributes(Package modelOrProfile, Classifier classifier, PersistentTable binding) {
		EList<Column> columns = binding.getColumns();
		for (Column c : columns) {
			if (nameGenerator.isInteresting(c)) {
				if (!(c.isPartOfPrimaryKey() || c.isPartOfForeignKey())) {
					Property attr = findAttribute(c, classifier.getAttributes());
					if (attr == null) {
						attr = createAttribute(classifier, c);
					}
					databaseElements.remove(attr);
					if (factory.getAttributeStereotype() != null) {
						if (!attr.isStereotypeApplied(factory.getAttributeStereotype())) {
							attr.applyStereotype(factory.getAttributeStereotype());
						}
						attr.setValue(factory.getAttributeStereotype(), "persistentName", c.getName());
					}
					attr.setType(factory.getDataTypeFor(c));
					attr.setIsReadOnly(false);
					attr.setIsDerived(false);
					attr.setLower(c.isNullable() ? 0 : 1);
					attr.setUpper(1);
				}
			}
		}
	}

	private void populateAssociations(Package modelOrProfile, Classifier fromClass, PersistentTable table) {
		List<ForeignKey> foreignKeys = table.getForeignKeys();
		for (ForeignKey foreignKey : foreignKeys) {
			if (nameGenerator.isInteresting(foreignKey) && nameGenerator.isInteresting(getReferencedTable(foreignKey))) {
				Association ass = findOrCreateAssociation(fromClass, foreignKey);
				Property toEnd = populateToEnd(foreignKey, ass);
				populateFromEnd(foreignKey, ass, toEnd);
				determineNavigability(toEnd);
				determineNavigability(toEnd.getOtherEnd());
			}
		}
	}

	private void determineNavigability(Property toEnd) {
		if (toEnd.getType() instanceof Enumeration) {
			toEnd.getOtherEnd().setIsNavigable(false);
		}
	}

	private Association findOrCreateAssociation(Classifier fromClass, ForeignKey foreignKey) {
		Association ass = null;
		if (fromClass.getOwner() instanceof Package) {
			String expectedName = nameGenerator.calcAssociationName(foreignKey);
			EList<? extends Type> ownedTypes = ((Package) fromClass.getOwner()).getOwnedTypes();
			ass = EmfColumnUtil.findAssociation(fromClass, foreignKey);
			if (ass == null) {
				ass = createAssociation(foreignKey, ownedTypes);
			}
			if (!ass.isStereotypeApplied(factory.getAssociationStereotype())) {
				ass.applyStereotype(factory.getAssociationStereotype());
				ass.setValue(factory.getAssociationStereotype(), "persistentName", expectedName);
			}
		} else {
			throw new IllegalStateException("Entities have to reside in Packages");
		}
		databaseElements.remove(ass);
		return ass;
	}

	private void populateFromEnd(ForeignKey foreignKey, Association ass, Property toOne) {
		Classifier fromTable = factory.getClassifierFor((PersistentTable) foreignKey.getBaseTable());
		Property end;
		if (ass.getMemberEnds().size() == 1) {
			end = ass.createNavigableOwnedEnd(nameGenerator.calcAssociationEndName((PersistentTable) foreignKey.getBaseTable()), fromTable);
		} else {
			end = toOne.getOtherEnd();
		}
		end.setLower(0);
		boolean isOneToOne = EmfColumnUtil.isOneToOne(foreignKey);
		if (isOneToOne) {
			end.setUpper(1);
		} else {
			end.setUpper(LiteralUnlimitedNatural.UNLIMITED);
		}
		databaseElements.remove(end);

	}

	private Property populateToEnd(ForeignKey foreignKey, Association ass) {
		EList<Column> members = foreignKey.getMembers();
		Property toOne = findAssociationEnd(foreignKey, ass.getMemberEnds());
		if (toOne == null) {
			Classifier toClassifier = factory.getClassifierFor(getReferencedTable(foreignKey));
			toOne = ass.createNavigableOwnedEnd(nameGenerator.calcAssociationEndName(foreignKey), toClassifier);
		}
		if (factory.getAssociationEndStereotype() != null) {
			if (!toOne.isStereotypeApplied(factory.getAssociationEndStereotype())) {
				toOne.applyStereotype(factory.getAssociationEndStereotype());
			}
			toOne.setValue(factory.getAssociationEndStereotype(), LINK_PERSISTENT_NAME, foreignKey.getName());
			EList<EObject> linkedProperties = (EList<EObject>) toOne.getValue(factory.getAssociationEndStereotype(), LINKED_PROPERTIES);
			EClass dt = (EClass) factory.getAssociationEndStereotype().getDefinition().getEStructuralFeature(LINKED_PROPERTIES).getEType();
			linkedProperties.clear();
			for (int i = 0; i < members.size(); i++) {
				EObject linkedProperty = EcoreUtil.create(dt);
				linkedProperty.eSet(dt.getEStructuralFeature(SOURCE_PERSISTENT_NAME), members.get(i).getName());
				EList<Column> referencedMembers = foreignKey.getReferencedMembers();
				if (referencedMembers.isEmpty()) {
					referencedMembers = foreignKey.getUniqueConstraint().getMembers();
				}
				Property targetProperty = EmfColumnUtil.findTargetProperty(toOne, referencedMembers.get(i));
				linkedProperty.eSet(dt.getEStructuralFeature(TARGET_PROPERTY), targetProperty);
				linkedProperties.add(linkedProperty);
			}
		}
		toOne.setUpper(1);
		toOne.setLower(0);
		for (Column column : members) {
			if (!column.isNullable()) {
				toOne.setLower(1);
			}
		}
		databaseElements.remove(toOne);

		return toOne;
	}

	@SuppressWarnings("rawtypes")
	private Association createAssociation(ForeignKey foreignKey, EList<? extends Type> ownedTypes) {
		Association ass = UMLFactory.eINSTANCE.createAssociation();
		String value = nameGenerator.calcAssociationName(foreignKey);
		ass.setName(value);
		((Collection) ownedTypes).add(ass);
		return ass;
	}

	private PersistentTable getReferencedTable(ForeignKey foreignKey) {
		PersistentTable referencedTable;
		if (foreignKey.getReferencedTable() == null) {
			referencedTable = (PersistentTable) foreignKey.getUniqueConstraint().getBaseTable();
		} else {
			referencedTable = (PersistentTable) foreignKey.getReferencedTable();
		}
		return referencedTable;
	}

	private Property findAssociationEnd(ForeignKey fk, List<? extends Property> attributes) {
		return EmfColumnUtil.findAssociationEnd(fk, attributes, nameGenerator.calcAssociationEndName(fk));
	}

	private Property findAttribute(Column column, EList<Property> attributes) {
		return EmfColumnUtil.findAttribute(column, attributes, nameGenerator.calcAttributeName(column));
	}

	private Property createAttribute(Classifier cls, Column pd) {
		Property attr = null;
		if (cls instanceof org.eclipse.uml2.uml.Class) {
			attr = ((org.eclipse.uml2.uml.Class) cls).getOwnedAttribute(nameGenerator.calcAttributeName(pd), factory.getDataTypeFor(pd), false,
					UMLPackage.eINSTANCE.getProperty(), true);
		} else if (cls instanceof org.eclipse.uml2.uml.DataType) {
			attr = ((org.eclipse.uml2.uml.DataType) cls).getOwnedAttribute(nameGenerator.calcAttributeName(pd), factory.getDataTypeFor(pd), false,
					UMLPackage.eINSTANCE.getProperty(), true);
		}
		return attr;
	}
}
