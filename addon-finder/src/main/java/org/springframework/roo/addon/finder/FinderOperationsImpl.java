package org.springframework.roo.addon.finder;

import static org.springframework.roo.model.RooJavaType.ROO_JPA_ACTIVE_RECORD;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.apache.commons.lang3.Validate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.springframework.roo.addon.jpa.activerecord.JpaActiveRecordMetadata;
import org.springframework.roo.classpath.PhysicalTypeIdentifier;
import org.springframework.roo.classpath.PhysicalTypeMetadata;
import org.springframework.roo.classpath.TypeLocationService;
import org.springframework.roo.classpath.TypeManagementService;
import org.springframework.roo.classpath.details.ClassOrInterfaceTypeDetails;
import org.springframework.roo.classpath.details.ClassOrInterfaceTypeDetailsBuilder;
import org.springframework.roo.classpath.details.FieldMetadata;
import org.springframework.roo.classpath.details.MemberFindingUtils;
import org.springframework.roo.classpath.details.annotations.AnnotationAttributeValue;
import org.springframework.roo.classpath.details.annotations.AnnotationMetadata;
import org.springframework.roo.classpath.details.annotations.AnnotationMetadataBuilder;
import org.springframework.roo.classpath.details.annotations.ArrayAttributeValue;
import org.springframework.roo.classpath.details.annotations.StringAttributeValue;
import org.springframework.roo.classpath.persistence.PersistenceMemberLocator;
import org.springframework.roo.classpath.scanner.MemberDetails;
import org.springframework.roo.classpath.scanner.MemberDetailsScanner;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.model.JavaSymbolName;
import org.springframework.roo.model.JavaType;
import org.springframework.roo.project.FeatureNames;
import org.springframework.roo.project.LogicalPath;
import org.springframework.roo.project.ProjectOperations;
import org.springframework.roo.support.logging.HandlerUtils;

/**
 * Implementation of {@link FinderOperations}.
 * 
 * @author Stefan Schmidt
 * @since 1.0
 */
@Component
@Service
public class FinderOperationsImpl implements FinderOperations {

    private static final Logger LOGGER = HandlerUtils
            .getLogger(FinderOperationsImpl.class);

    @Reference private DynamicFinderServices dynamicFinderServices;
    @Reference private MemberDetailsScanner memberDetailsScanner;
    @Reference private MetadataService metadataService;
    @Reference private PersistenceMemberLocator persistenceMemberLocator;
    @Reference private ProjectOperations projectOperations;
    @Reference private TypeLocationService typeLocationService;
    @Reference private TypeManagementService typeManagementService;

    private String getErrorMsg() {
        return "Annotation " + ROO_JPA_ACTIVE_RECORD.getSimpleTypeName()
                + " attribute 'finders' must be an array of strings";
    }

    public void installFinder(final JavaType typeName,
            final JavaSymbolName finderName) {
        Validate.notNull(typeName, "Java type required");
        Validate.notNull(finderName, "Finer name required");

        final String id = typeLocationService
                .getPhysicalTypeIdentifier(typeName);
        if (id == null) {
            LOGGER.warning("Cannot locate source for '"
                    + typeName.getFullyQualifiedTypeName() + "'");
            return;
        }

        // Go and get the entity metadata, as any type with finders has to be an
        // entity
        final JavaType javaType = PhysicalTypeIdentifier.getJavaType(id);
        final LogicalPath path = PhysicalTypeIdentifier.getPath(id);
        final String entityMid = JpaActiveRecordMetadata.createIdentifier(
                javaType, path);

        // Get the entity metadata
        final JpaActiveRecordMetadata jpaActiveRecordMetadata = (JpaActiveRecordMetadata) metadataService
                .get(entityMid);
        if (jpaActiveRecordMetadata == null) {
            LOGGER.warning("Cannot provide finders because '"
                    + typeName.getFullyQualifiedTypeName()
                    + "' is not an entity - " + entityMid);
            return;
        }

        // We know the file exists, as there's already entity metadata for it
        final ClassOrInterfaceTypeDetails cid = typeLocationService
                .getTypeDetails(id);
        if (cid == null) {
            throw new IllegalArgumentException("Cannot locate source for '"
                    + javaType.getFullyQualifiedTypeName() + "'");
        }

        // We know there should be an existing RooEntity annotation
        final List<? extends AnnotationMetadata> annotations = cid
                .getAnnotations();
        final AnnotationMetadata jpaActiveRecordAnnotation = MemberFindingUtils
                .getAnnotationOfType(annotations, ROO_JPA_ACTIVE_RECORD);
        if (jpaActiveRecordAnnotation == null) {
            LOGGER.warning("Unable to find the entity annotation on '"
                    + typeName.getFullyQualifiedTypeName() + "'");
            return;
        }

        // Confirm they typed a valid finder name
        final MemberDetails memberDetails = memberDetailsScanner
                .getMemberDetails(getClass().getName(), cid);
        if (dynamicFinderServices.getQueryHolder(memberDetails, finderName,
                jpaActiveRecordMetadata.getPlural(),
                jpaActiveRecordMetadata.getEntityName()) == null) {
            LOGGER.warning("Finder name '" + finderName.getSymbolName()
                    + "' either does not exist or contains an error");
            return;
        }

        // Make a destination list to store our final attributes
        final List<AnnotationAttributeValue<?>> attributes = new ArrayList<AnnotationAttributeValue<?>>();
        final List<StringAttributeValue> desiredFinders = new ArrayList<StringAttributeValue>();

        // Copy the existing attributes, excluding the "finder" attribute
        boolean alreadyAdded = false;
        final AnnotationAttributeValue<?> val = jpaActiveRecordAnnotation
                .getAttribute(new JavaSymbolName("finders"));
        if (val != null) {
            // Ensure we have an array of strings
            if (!(val instanceof ArrayAttributeValue<?>)) {
                LOGGER.warning(getErrorMsg());
                return;
            }
            final ArrayAttributeValue<?> arrayVal = (ArrayAttributeValue<?>) val;
            for (final Object o : arrayVal.getValue()) {
                if (!(o instanceof StringAttributeValue)) {
                    LOGGER.warning(getErrorMsg());
                    return;
                }
                final StringAttributeValue sv = (StringAttributeValue) o;
                if (sv.getValue().equals(finderName.getSymbolName())) {
                    alreadyAdded = true;
                }
                desiredFinders.add(sv);
            }
        }

        // Add the desired finder to the end
        if (!alreadyAdded) {
            desiredFinders.add(new StringAttributeValue(new JavaSymbolName(
                    "ignored"), finderName.getSymbolName()));
        }

        // Now let's add the "finders" attribute
        attributes.add(new ArrayAttributeValue<StringAttributeValue>(
                new JavaSymbolName("finders"), desiredFinders));

        final ClassOrInterfaceTypeDetailsBuilder cidBuilder = new ClassOrInterfaceTypeDetailsBuilder(
                cid);
        final AnnotationMetadataBuilder annotation = new AnnotationMetadataBuilder(
                ROO_JPA_ACTIVE_RECORD, attributes);
        cidBuilder.updateTypeAnnotation(annotation.build(),
                new HashSet<JavaSymbolName>());
        typeManagementService.createOrUpdateTypeOnDisk(cidBuilder.build());
    }

    public boolean isFinderInstallationPossible() {
        return projectOperations.isFocusedProjectAvailable()
                && projectOperations
                        .isFeatureInstalledInFocusedModule(FeatureNames.JPA);
    }

    public SortedSet<String> listFindersFor(final JavaType typeName,
            final Integer depth) {
        Validate.notNull(typeName, "Java type required");

        final String id = typeLocationService
                .getPhysicalTypeIdentifier(typeName);
        if (id == null) {
            throw new IllegalArgumentException("Cannot locate source for '"
                    + typeName.getFullyQualifiedTypeName() + "'");
        }

        // Go and get the entity metadata, as any type with finders has to be an
        // entity
        final JavaType javaType = PhysicalTypeIdentifier.getJavaType(id);
        final LogicalPath path = PhysicalTypeIdentifier.getPath(id);
        final String entityMid = JpaActiveRecordMetadata.createIdentifier(
                javaType, path);

        // Get the entity metadata
        final JpaActiveRecordMetadata jpaActiveRecordMetadata = (JpaActiveRecordMetadata) metadataService
                .get(entityMid);
        if (jpaActiveRecordMetadata == null) {
            throw new IllegalArgumentException(
                    "Cannot provide finders because '"
                            + typeName.getFullyQualifiedTypeName()
                            + "' is not an entity");
        }

        // Get the member details
        final PhysicalTypeMetadata physicalTypeMetadata = (PhysicalTypeMetadata) metadataService
                .get(PhysicalTypeIdentifier.createIdentifier(javaType, path));
        if (physicalTypeMetadata == null) {
            throw new IllegalStateException(
                    "Could not determine physical type metadata for type "
                            + javaType);
        }
        final ClassOrInterfaceTypeDetails cid = physicalTypeMetadata
                .getMemberHoldingTypeDetails();
        if (cid == null) {
            throw new IllegalStateException(
                    "Could not determine class or interface type details for type "
                            + javaType);
        }
        final MemberDetails memberDetails = memberDetailsScanner
                .getMemberDetails(getClass().getName(), cid);
        final List<FieldMetadata> idFields = persistenceMemberLocator
                .getIdentifierFields(javaType);
        final FieldMetadata versionField = persistenceMemberLocator
                .getVersionField(javaType);

        // Compute the finders (excluding the ID, version, and EM fields)
        final Set<JavaSymbolName> exclusions = new HashSet<JavaSymbolName>();
        exclusions.add(jpaActiveRecordMetadata.getEntityManagerField()
                .getFieldName());
        for (final FieldMetadata idField : idFields) {
            exclusions.add(idField.getFieldName());
        }

        if (versionField != null) {
            exclusions.add(versionField.getFieldName());
        }

        final SortedSet<String> result = new TreeSet<String>();

        final List<JavaSymbolName> finders = dynamicFinderServices.getFinders(
                memberDetails, jpaActiveRecordMetadata.getPlural(), depth,
                exclusions);
        for (final JavaSymbolName finder : finders) {
            // Avoid displaying problematic finders
            try {
                final QueryHolder queryHolder = dynamicFinderServices
                        .getQueryHolder(memberDetails, finder,
                                jpaActiveRecordMetadata.getPlural(),
                                jpaActiveRecordMetadata.getEntityName());
                final List<JavaSymbolName> parameterNames = queryHolder
                        .getParameterNames();
                final List<JavaType> parameterTypes = queryHolder
                        .getParameterTypes();
                final StringBuilder signature = new StringBuilder();
                int x = -1;
                for (final JavaType param : parameterTypes) {
                    x++;
                    if (x > 0) {
                        signature.append(", ");
                    }
                    signature.append(param.getSimpleTypeName()).append(" ")
                            .append(parameterNames.get(x).getSymbolName());
                }
                result.add(finder.getSymbolName() + "(" + signature + ")" /*
                                                                           * query:
                                                                           * '"
                                                                           * +
                                                                           * query
                                                                           * +
                                                                           * "'"
                                                                           */);
            }
            catch (final RuntimeException e) {
                result.add(finder.getSymbolName() + " - failure");
            }
        }
        return result;
    }
}
