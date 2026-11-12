/*
 * EverBook — a modified fork of Book's Story, a free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * Modified by ByteFlipper for EverBook
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.byteflipper.everbook.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import com.byteflipper.everbook.data.local.room.CategoryDao
import com.byteflipper.everbook.data.mapper.category.CategoryMapper
import com.byteflipper.everbook.domain.library.custom_category.Category
import com.byteflipper.everbook.domain.repository.CategoryRepository
import com.byteflipper.everbook.domain.library.display.LibrarySortOrder
import com.byteflipper.everbook.data.local.dto.CategoryEntity
import com.byteflipper.everbook.data.local.room.BookDao
import com.byteflipper.everbook.data.local.room.BookCategoryDao
import com.byteflipper.everbook.data.local.room.BookDatabase

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val mapper: CategoryMapper,
    private val database: BookDatabase,
    private val bookDao: BookDao,
    private val bookCategoryDao: BookCategoryDao
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> {
        return categoryDao.observeCategories()
            .map { entities ->
                entities.map { mapper.toDomain(it) }
            }
    }

    override suspend fun createCategory(name: String) {
        val position = (categoryDao.getMaxPosition() ?: -1) + 1
        categoryDao.insert(
            CategoryEntity(
                name = name,
                kind = "CUSTOM",
                position = position
            )
        )
    }

    override suspend fun renameCategory(id: Int, newName: String) {
        val entity = categoryDao.findById(id) ?: return
        if (entity.kind == "SYSTEM_MAIN") return
        categoryDao.update(entity.copy(name = newName))
    }

    override suspend fun updateCategorySort(
        id: Int,
        sortOrder: LibrarySortOrder,
        sortOrderDescending: Boolean
    ) {
        val entity = categoryDao.findById(id) ?: return
        categoryDao.update(
            entity.copy(
                sortOrder = sortOrder,
                sortOrderDescending = sortOrderDescending
            )
        )
    }

    override suspend fun deleteCategory(id: Int, targetId: Int?) {
        val entity = categoryDao.findById(id) ?: return
        // Only user-created (CUSTOM) categories can be deleted. The "All" category (SYSTEM_MAIN)
        // and the default system categories (SYSTEM: Reading / Already read / Planning / Dropped)
        // are permanent — deleting them would orphan books and break the library tabs.
        if (entity.kind != "CUSTOM") return
        val destinationId = targetId
            ?.takeIf { it != id && categoryDao.findById(it) != null }
            ?: 0

        database.withTransaction {
            bookCategoryDao.copyCategoryMembers(sourceId = id, targetId = destinationId)
            bookCategoryDao.deleteByCategory(id)
            bookDao.replacePrimaryCategory(sourceId = id, targetId = destinationId)
            categoryDao.delete(entity)
        }
    }

    override suspend fun reorderCategories(order: List<Int>) {
        order.forEachIndexed { index, categoryId ->
            val entity = categoryDao.findById(categoryId) ?: return@forEachIndexed
            categoryDao.update(entity.copy(position = index))
        }
    }
    
    override suspend fun updateCategoriesPositions(idToPositionMap: Map<Int, Int>) {
        categoryDao.updatePositions(idToPositionMap)
    }

    override suspend fun toggleCategoryVisibility(id: Int, visible: Boolean) {
        val entity = categoryDao.findById(id) ?: return
        categoryDao.update(entity.copy(isVisible = visible))
    }
} 
