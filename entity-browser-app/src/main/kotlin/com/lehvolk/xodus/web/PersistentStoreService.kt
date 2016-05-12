package com.lehvolk.xodus.web


import jetbrains.exodus.entitystore.*
import jetbrains.exodus.env.Environments
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream

class PersistentStoreService {

    private val log = LoggerFactory.getLogger(this.javaClass)

    private var store: PersistentEntityStoreImpl? = null

    fun construct() {
        val requisites = XodusStore.lookupRequisites()
        if (requisites != null) {
            init(requisites)
        }
    }

    fun update() {
        val current = XodusStore.current()
        if (current != null) {
            init(current)
        }
    }

    fun init(requisites: XodusStoreRequisites) {
        try {
            store = PersistentEntityStores.newInstance(
                    Environments.newInstance(requisites.location), requisites.key)
        } catch (e: RuntimeException) {
            val msg = "Can't get valid Xodus entity store location and store key. Check the configuration"
            log.error(msg, e)
            throw IllegalStateException(msg, e)
        }
    }

    fun destroy() {
        var proceed = true
        var count = 0
        while (proceed && count < 10) {
            try {
                log.info("trying to close persistent store. attempt {}", count)
                store?.close()
                proceed = false
                log.info("persistent store closed")
            } catch (e: RuntimeException) {
                log.error("error closing persistent store", e)
                count++
            }

        }
    }

    fun isInited(): Boolean {
        return store != null
    }

    fun allTypes(): Array<EntityType> {
        if (store == null) {
            return emptyArray()
        }
        return readonly { tx -> tx.entityTypes.map { it.asEntityType(tx.store) }.toTypedArray() }
    }

    fun searchType(typeId: Int, term: String?, offset: Int, pageSize: Int): SearchPager {
        return readonly { t ->
            val type = store!!.getEntityType(t, typeId)
            val result = SmartSearchToolkit.doSmartSearch(term, type, typeId, t)
            val totalCount = result.size()
            val items = result.skip(offset).take(pageSize).map { it.asView() }
            SearchPager(items.toTypedArray(), totalCount)
        }
    }

    fun newEntity(typeId: Int, vo: ChangeSummary): EntityView {
        val entityId = transactional { t ->
            val type = store!!.getEntityType(t, typeId)
            val entity = t.newEntity(type)
            vo.properties.added.forEach { entity.applyValues(it) }
            vo.links.added.forEach {
                val link = getEntity(it.typeId, it.entityId, t)
                entity.addLink(it.name!!, link)
            }
            entity.id.localId
        }
        return getEntity(typeId, entityId)
    }

    @Throws(IOException::class)
    fun getBlob(typeId: Int, entityId: Long, blobName: String, out: OutputStream) {
        val tx = store!!.beginReadonlyTransaction()
        try {
            val entity = getEntity(typeId, entityId, tx)
            entity.getBlob(blobName)?.copyTo(out, bufferSize = 4096)
        } finally {
            tx.commit()
        }
    }

    private fun PersistentEntity.applyValues(property: EntityProperty) {
        property.value = safeTrim(property.value)
        val value = property.string2value()
        if (value != null) {
            this.setProperty(property.name!!, value)
        }
    }

    fun updateEntity(typeId: Int, entityId: Long, vo: ChangeSummary): EntityView {
        val localId = transactional { t ->
            val entity = getEntity(typeId, entityId, t)

            vo.properties.deleted.filter { entity.has(it) }.map { it.name }.forEach { entity.deleteProperty(it!!) }
            vo.properties.added.filter { !entity.has(it) }.forEach { entity.applyValues(it) }
            vo.properties.modified.filter { entity.has(it) }.forEach { entity.applyValues(it) }

            val links = entity.linkNames
            vo.links.deleted.filter { links.contains(it.name) }.forEach {
                val linked = getEntity(it.typeId, it.entityId, t)
                entity.deleteLink(it.name!!, linked)
            }
            vo.links.added.filter { !links.contains(it.name) }.forEach {
                val id = PersistentEntityId(it.typeId, it.entityId)
                val link = t.getEntity(id)
                entity.addLink(it.name!!, link)
            }
            entityId
        }
        return getEntity(typeId, localId)
    }

    fun getEntity(typeId: Int, entityId: Long): EntityView {
        return transactional { t ->
            val entity: PersistentEntity = getEntity(typeId, entityId, t)
            entity.asView()
        }
    }

    fun deleteEntity(id: Int, entityId: Long) {
        transactional {
            val entity = getEntity(id, entityId, it)
            entity.delete()
            null
        }
    }

    private fun getEntity(typeId: Int, entityId: Long, t: PersistentStoreTransaction): PersistentEntity {
        try {
            return t.getEntity(PersistentEntityId(typeId, entityId))
        } catch (e: RuntimeException) {
            log.error("entity not found by type '$typeId' and entityId '$entityId'", e)
            throw EntityNotFoundException(e, typeId, entityId)
        }

    }

    private fun safeTrim(value: String?): String? {
        if (value == null) {
            return null
        }
        val trimmed = value.trim { it <= ' ' }
        return if (trimmed.isEmpty()) null else trimmed
    }

    private fun Entity.has(named: Named): Boolean {
        return this.propertyNames.contains(named.name)
    }


    private fun <T> transactional(call: (PersistentStoreTransaction) -> T): T {
        return store!!.computeInTransaction { call(it as PersistentStoreTransaction) }
    }

    private fun <T> readonly(call: (PersistentStoreTransaction) -> T): T {
        return store!!.computeInReadonlyTransaction { call(it as PersistentStoreTransaction) }
    }

}