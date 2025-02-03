/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.extension.persistence.impl.eclipselink;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicates;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.PolarisBaseEntity;
import org.apache.polaris.core.entity.PolarisChangeTrackingVersions;
import org.apache.polaris.core.entity.PolarisEntitiesActiveKey;
import org.apache.polaris.core.entity.PolarisEntityActiveRecord;
import org.apache.polaris.core.entity.PolarisEntityCore;
import org.apache.polaris.core.entity.PolarisEntityId;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PolarisGrantRecord;
import org.apache.polaris.core.entity.PolarisPrincipalSecrets;
import org.apache.polaris.core.exceptions.AlreadyExistsException;
import org.apache.polaris.core.persistence.PolarisMetaStoreManagerImpl;
import org.apache.polaris.core.persistence.PolarisMetaStoreSession;
import org.apache.polaris.core.persistence.PrincipalSecretsGenerator;
import org.apache.polaris.core.persistence.RetryOnConcurrencyException;
import org.apache.polaris.core.storage.PolarisStorageConfigurationInfo;
import org.apache.polaris.core.storage.PolarisStorageIntegration;
import org.apache.polaris.core.storage.PolarisStorageIntegrationProvider;
import org.apache.polaris.jpa.models.ModelEntity;
import org.apache.polaris.jpa.models.ModelEntityActive;
import org.apache.polaris.jpa.models.ModelEntityChangeTracking;
import org.apache.polaris.jpa.models.ModelGrantRecord;
import org.apache.polaris.jpa.models.ModelPrincipalSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EclipseLink implementation of a Polaris metadata store supporting persisting and retrieving all
 * Polaris metadata from/to the configured database systems.
 */
public class PolarisEclipseLinkMetaStoreSessionImpl implements PolarisMetaStoreSession {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(PolarisEclipseLinkMetaStoreSessionImpl.class);

  // Cache to hold the EntityManagerFactory for each realm. Each realm needs a separate
  // EntityManagerFactory since it connects to different databases
  private static final ConcurrentHashMap<String, EntityManagerFactory> realmFactories =
      new ConcurrentHashMap<>();
  private final EntityManagerFactory emf;
  private final ThreadLocal<EntityManager> localSession = new ThreadLocal<>();
  private final PolarisEclipseLinkStore store;
  private final PolarisStorageIntegrationProvider storageIntegrationProvider;
  private final PrincipalSecretsGenerator secretsGenerator;
  private final PolarisDiagnostics diagnostics;

  /**
   * Create a meta store session against provided realm. Each realm has its own database.
   *
   * @param store Backing store of EclipseLink implementation
   * @param storageIntegrationProvider Storage integration provider
   * @param realmContext Realm context used to communicate with different database.
   * @param confFile Optional EclipseLink configuration file. Default to 'META-INF/persistence.xml'.
   * @param persistenceUnitName Optional persistence-unit name in confFile. Default to 'polaris'.
   */
  public PolarisEclipseLinkMetaStoreSessionImpl(
      @Nonnull PolarisEclipseLinkStore store,
      @Nonnull PolarisStorageIntegrationProvider storageIntegrationProvider,
      @Nonnull RealmContext realmContext,
      @Nullable String confFile,
      @Nullable String persistenceUnitName,
      @Nonnull PrincipalSecretsGenerator secretsGenerator,
      @Nonnull PolarisDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
    LOGGER.debug(
        "Creating EclipseLink Meta Store Session for realm {}", realmContext.getRealmIdentifier());
    emf = createEntityManagerFactory(realmContext, confFile, persistenceUnitName);

    // init store
    this.store = store;
    try (EntityManager session = emf.createEntityManager()) {
      this.store.initialize(session);
    }
    this.storageIntegrationProvider = storageIntegrationProvider;
    this.secretsGenerator = secretsGenerator;
  }

  /**
   * Create EntityManagerFactory.
   *
   * <p>The EntityManagerFactory creation is expensive, so we are caching and reusing it for each
   * realm.
   */
  private EntityManagerFactory createEntityManagerFactory(
      @Nonnull RealmContext realmContext,
      @Nullable String confFile,
      @Nullable String persistenceUnitName) {
    String realm = realmContext.getRealmIdentifier();
    return realmFactories.computeIfAbsent(
        realm,
        key -> {
          try {
            PolarisEclipseLinkPersistenceUnit persistenceUnit =
                PolarisEclipseLinkPersistenceUnit.locatePersistenceUnit(
                    confFile, persistenceUnitName);
            return persistenceUnit.createEntityManagerFactory(realmContext);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  @VisibleForTesting
  static void clearEntityManagerFactories() {
    realmFactories.clear();
  }

  /** {@inheritDoc} */
  @Override
  public <T> T runInTransaction(@Nonnull Supplier<T> transactionCode) {
    diagnostics.check(localSession.get() == null, "cannot nest transaction");

    try (EntityManager session = emf.createEntityManager()) {
      localSession.set(session);
      EntityTransaction tr = session.getTransaction();
      try {
        tr.begin();

        T result = transactionCode.get();

        // Commit when it's not rolled back by the client
        if (session.getTransaction().isActive()) {
          tr.commit();
          LOGGER.debug("transaction committed");
        }

        return result;
      } catch (Exception e) {
        tr.rollback();
        LOGGER.debug("transaction rolled back", e);

        if (e instanceof OptimisticLockException
            || e.getCause() instanceof OptimisticLockException) {
          throw new RetryOnConcurrencyException(e);
        }

        throw e;
      } finally {
        localSession.remove();
      }
    } catch (PersistenceException e) {
      if (e.toString().toLowerCase(Locale.ROOT).contains("duplicate key")) {
        throw new AlreadyExistsException("Duplicate key error when persisting entity", e);
      } else {
        throw e;
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public void runActionInTransaction(@Nonnull Runnable transactionCode) {
    diagnostics.check(localSession.get() == null, "cannot nest transaction");

    try (EntityManager session = emf.createEntityManager()) {
      localSession.set(session);
      EntityTransaction tr = session.getTransaction();
      try {
        tr.begin();

        transactionCode.run();

        // Commit when it's not rolled back by the client
        if (session.getTransaction().isActive()) {
          tr.commit();
          LOGGER.debug("transaction committed");
        }
      } catch (Exception e) {
        LOGGER.debug("Rolling back transaction due to an error", e);
        tr.rollback();

        if (e instanceof OptimisticLockException
            || e.getCause() instanceof OptimisticLockException) {
          throw new RetryOnConcurrencyException(e);
        }

        throw e;
      } finally {
        localSession.remove();
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public <T> T runInReadTransaction(@Nonnull Supplier<T> transactionCode) {
    // EclipseLink doesn't support readOnly transaction
    return runInTransaction(transactionCode);
  }

  /** {@inheritDoc} */
  @Override
  public void runActionInReadTransaction(@Nonnull Runnable transactionCode) {
    // EclipseLink doesn't support readOnly transaction
    runActionInTransaction(transactionCode);
  }

  /**
   * @return new unique entity identifier
   */
  @Override
  public long generateNewId() {
    // This function can be called within a transaction or out of transaction.
    // If called out of transaction, create a new transaction, otherwise run in current transaction
    return localSession.get() != null
        ? this.store.getNextSequence(localSession.get())
        : runInReadTransaction(this::generateNewId);
  }

  /** {@inheritDoc} */
  @Override
  public void writeToEntities(@Nonnull PolarisBaseEntity entity) {
    this.store.writeToEntities(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public <T extends PolarisStorageConfigurationInfo> void persistStorageIntegrationIfNeeded(
      @Nonnull PolarisBaseEntity entity,
      @Nullable PolarisStorageIntegration<T> storageIntegration) {
    // not implemented for eclipselink store
  }

  /** {@inheritDoc} */
  @Override
  public void writeToEntitiesActive(@Nonnull PolarisBaseEntity entity) {
    // write it
    this.store.writeToEntitiesActive(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public void writeToEntitiesDropped(@Nonnull PolarisBaseEntity entity) {
    // write it
    this.store.writeToEntitiesDropped(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public void writeToEntitiesChangeTracking(@Nonnull PolarisBaseEntity entity) {
    // write it
    this.store.writeToEntitiesChangeTracking(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public void writeToGrantRecords(@Nonnull PolarisGrantRecord grantRec) {
    // write it
    this.store.writeToGrantRecords(localSession.get(), grantRec);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteFromEntities(@Nonnull PolarisEntityCore entity) {

    // delete it
    this.store.deleteFromEntities(localSession.get(), entity.getCatalogId(), entity.getId());
  }

  /** {@inheritDoc} */
  @Override
  public void deleteFromEntitiesActive(@Nonnull PolarisEntityCore entity) {
    // delete it
    this.store.deleteFromEntitiesActive(localSession.get(), new PolarisEntitiesActiveKey(entity));
  }

  /** {@inheritDoc} */
  @Override
  public void deleteFromEntitiesDropped(@Nonnull PolarisBaseEntity entity) {
    // delete it
    this.store.deleteFromEntitiesDropped(localSession.get(), entity.getCatalogId(), entity.getId());
  }

  /**
   * {@inheritDoc}
   *
   * @param entity entity record to delete
   */
  @Override
  public void deleteFromEntitiesChangeTracking(@Nonnull PolarisEntityCore entity) {
    // delete it
    this.store.deleteFromEntitiesChangeTracking(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteFromGrantRecords(@Nonnull PolarisGrantRecord grantRec) {
    this.store.deleteFromGrantRecords(localSession.get(), grantRec);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteAllEntityGrantRecords(
      @Nonnull PolarisEntityCore entity,
      @Nonnull List<PolarisGrantRecord> grantsOnGrantee,
      @Nonnull List<PolarisGrantRecord> grantsOnSecurable) {
    this.store.deleteAllEntityGrantRecords(localSession.get(), entity);
  }

  /** {@inheritDoc} */
  @Override
  public void deleteAll() {
    this.store.deleteAll(localSession.get());
  }

  /** {@inheritDoc} */
  @Override
  public @Nullable PolarisBaseEntity lookupEntity(long catalogId, long entityId) {
    return ModelEntity.toEntity(this.store.lookupEntity(localSession.get(), catalogId, entityId));
  }

  @Override
  public @Nonnull List<PolarisBaseEntity> lookupEntities(List<PolarisEntityId> entityIds) {
    return this.store.lookupEntities(localSession.get(), entityIds).stream()
        .map(ModelEntity::toEntity)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public int lookupEntityVersion(long catalogId, long entityId) {
    ModelEntity model = this.store.lookupEntity(localSession.get(), catalogId, entityId);
    return model == null ? 0 : model.getEntityVersion();
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull List<PolarisChangeTrackingVersions> lookupEntityVersions(
      List<PolarisEntityId> entityIds) {
    Map<PolarisEntityId, ModelEntity> idToEntityMap =
        this.store.lookupEntities(localSession.get(), entityIds).stream()
            .collect(
                Collectors.toMap(
                    entry -> new PolarisEntityId(entry.getCatalogId(), entry.getId()),
                    entry -> entry));
    return entityIds.stream()
        .map(
            entityId -> {
              ModelEntity entity = idToEntityMap.getOrDefault(entityId, null);
              return entity == null
                  ? null
                  : new PolarisChangeTrackingVersions(
                      entity.getEntityVersion(), entity.getGrantRecordsVersion());
            })
        .collect(Collectors.toList());
  }

  /** {@inheritDoc} */
  @Override
  @Nullable
  public PolarisEntityActiveRecord lookupEntityActive(
      @Nonnull PolarisEntitiesActiveKey entityActiveKey) {
    // lookup the active entity slice
    return ModelEntityActive.toEntityActive(
        this.store.lookupEntityActive(localSession.get(), entityActiveKey));
  }

  /** {@inheritDoc} */
  @Override
  @Nonnull
  public List<PolarisEntityActiveRecord> lookupEntityActiveBatch(
      @Nonnull List<PolarisEntitiesActiveKey> entityActiveKeys) {
    // now build a list to quickly verify that nothing has changed
    return entityActiveKeys.stream().map(this::lookupEntityActive).collect(Collectors.toList());
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull List<PolarisEntityActiveRecord> listActiveEntities(
      long catalogId, long parentId, @Nonnull PolarisEntityType entityType) {
    return listActiveEntities(catalogId, parentId, entityType, Predicates.alwaysTrue());
  }

  @Override
  public @Nonnull List<PolarisEntityActiveRecord> listActiveEntities(
      long catalogId,
      long parentId,
      @Nonnull PolarisEntityType entityType,
      @Nonnull Predicate<PolarisBaseEntity> entityFilter) {
    // full range scan under the parent for that type
    return listActiveEntities(
        catalogId,
        parentId,
        entityType,
        Integer.MAX_VALUE,
        entityFilter,
        entity ->
            new PolarisEntityActiveRecord(
                entity.getCatalogId(),
                entity.getId(),
                entity.getParentId(),
                entity.getName(),
                entity.getTypeCode(),
                entity.getSubTypeCode()));
  }

  @Override
  public @Nonnull <T> List<T> listActiveEntities(
      long catalogId,
      long parentId,
      @Nonnull PolarisEntityType entityType,
      int limit,
      @Nonnull Predicate<PolarisBaseEntity> entityFilter,
      @Nonnull Function<PolarisBaseEntity, T> transformer) {
    // full range scan under the parent for that type
    return this.store
        .lookupFullEntitiesActive(localSession.get(), catalogId, parentId, entityType)
        .stream()
        .map(ModelEntity::toEntity)
        .filter(entityFilter)
        .limit(limit)
        .map(transformer)
        .collect(Collectors.toList());
  }

  /** {@inheritDoc} */
  @Override
  public boolean hasChildren(
      @Nullable PolarisEntityType entityType, long catalogId, long parentId) {
    // check if it has children
    return this.store.countActiveChildEntities(localSession.get(), catalogId, parentId, entityType)
        > 0;
  }

  /** {@inheritDoc} */
  @Override
  public int lookupEntityGrantRecordsVersion(long catalogId, long entityId) {
    ModelEntityChangeTracking entity =
        this.store.lookupEntityChangeTracking(localSession.get(), catalogId, entityId);

    // does not exist, 0
    return entity == null ? 0 : entity.getGrantRecordsVersion();
  }

  /** {@inheritDoc} */
  @Override
  public @Nullable PolarisGrantRecord lookupGrantRecord(
      long securableCatalogId,
      long securableId,
      long granteeCatalogId,
      long granteeId,
      int privilegeCode) {
    // lookup the grants records slice to find the usage role
    return ModelGrantRecord.toGrantRecord(
        this.store.lookupGrantRecord(
            localSession.get(),
            securableCatalogId,
            securableId,
            granteeCatalogId,
            granteeId,
            privilegeCode));
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull List<PolarisGrantRecord> loadAllGrantRecordsOnSecurable(
      long securableCatalogId, long securableId) {
    // now fetch all grants for this securable
    return this.store
        .lookupAllGrantRecordsOnSecurable(localSession.get(), securableCatalogId, securableId)
        .stream()
        .map(ModelGrantRecord::toGrantRecord)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull List<PolarisGrantRecord> loadAllGrantRecordsOnGrantee(
      long granteeCatalogId, long granteeId) {
    // now fetch all grants assigned to this grantee
    return this.store
        .lookupGrantRecordsOnGrantee(localSession.get(), granteeCatalogId, granteeId)
        .stream()
        .map(ModelGrantRecord::toGrantRecord)
        .toList();
  }

  /** {@inheritDoc} */
  @Override
  public @Nullable PolarisPrincipalSecrets loadPrincipalSecrets(@Nonnull String clientId) {
    return ModelPrincipalSecrets.toPrincipalSecrets(
        this.store.lookupPrincipalSecrets(localSession.get(), clientId));
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull PolarisPrincipalSecrets generateNewPrincipalSecrets(
      @Nonnull String principalName, long principalId) {
    // ensure principal client id is unique
    PolarisPrincipalSecrets principalSecrets;
    ModelPrincipalSecrets lookupPrincipalSecrets;
    do {
      // generate new random client id and secrets
      principalSecrets = secretsGenerator.produceSecrets(principalName, principalId);

      // load the existing secrets
      lookupPrincipalSecrets =
          this.store.lookupPrincipalSecrets(
              localSession.get(), principalSecrets.getPrincipalClientId());
    } while (lookupPrincipalSecrets != null);

    // write new principal secrets
    this.store.writePrincipalSecrets(localSession.get(), principalSecrets);

    // if not found, return null
    return principalSecrets;
  }

  /** {@inheritDoc} */
  @Override
  public @Nonnull PolarisPrincipalSecrets rotatePrincipalSecrets(
      @Nonnull String clientId, long principalId, boolean reset, @Nonnull String oldSecretHash) {

    // load the existing secrets
    PolarisPrincipalSecrets principalSecrets =
        ModelPrincipalSecrets.toPrincipalSecrets(
            this.store.lookupPrincipalSecrets(localSession.get(), clientId));

    // should be found
    diagnostics.checkNotNull(
        principalSecrets,
        "cannot_find_secrets",
        "client_id={} principalId={}",
        clientId,
        principalId);

    // ensure principal id is matching
    diagnostics.check(
        principalId == principalSecrets.getPrincipalId(),
        "principal_id_mismatch",
        "expectedId={} id={}",
        principalId,
        principalSecrets.getPrincipalId());

    // rotate the secrets
    principalSecrets.rotateSecrets(oldSecretHash);
    if (reset) {
      principalSecrets.rotateSecrets(principalSecrets.getMainSecretHash());
    }

    // write back new secrets
    this.store.writePrincipalSecrets(localSession.get(), principalSecrets);

    // return those
    return principalSecrets;
  }

  /** {@inheritDoc} */
  @Override
  public void deletePrincipalSecrets(@Nonnull String clientId, long principalId) {
    // load the existing secrets
    ModelPrincipalSecrets principalSecrets =
        this.store.lookupPrincipalSecrets(localSession.get(), clientId);

    // should be found
    diagnostics.checkNotNull(
        principalSecrets,
        "cannot_find_secrets",
        "client_id={} principalId={}",
        clientId,
        principalId);

    // ensure principal id is matching
    diagnostics.check(
        principalId == principalSecrets.getPrincipalId(),
        "principal_id_mismatch",
        "expectedId={} id={}",
        principalId,
        principalSecrets.getPrincipalId());

    // delete these secrets
    this.store.deletePrincipalSecrets(localSession.get(), clientId);
  }

  /** {@inheritDoc} */
  @Override
  public @Nullable <T extends PolarisStorageConfigurationInfo>
      PolarisStorageIntegration<T> createStorageIntegration(
          long catalogId,
          long entityId,
          PolarisStorageConfigurationInfo polarisStorageConfigurationInfo) {
    return storageIntegrationProvider.getStorageIntegrationForConfig(
        polarisStorageConfigurationInfo);
  }

  /** {@inheritDoc} */
  @Override
  public @Nullable <T extends PolarisStorageConfigurationInfo>
      PolarisStorageIntegration<T> loadPolarisStorageIntegration(
          @Nonnull PolarisBaseEntity entity) {
    PolarisStorageConfigurationInfo storageConfig =
        PolarisMetaStoreManagerImpl.readStorageConfiguration(diagnostics, entity);
    return storageIntegrationProvider.getStorageIntegrationForConfig(storageConfig);
  }

  @Override
  public void rollback() {
    EntityManager session = localSession.get();
    if (session != null) {
      session.getTransaction().rollback();
    }
  }
}
