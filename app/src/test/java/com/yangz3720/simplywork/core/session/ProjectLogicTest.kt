package com.yangz3720.simplywork.core.session

import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目逻辑（v1.3）：名字校验、去重、默认项目、切换语义。
 * [ProjectStore] 的 SharedPreferences 壳用桩（[InMemoryPrefs]）跑真实持久化行为。
 */
class ProjectLogicTest {

    @Test
    fun `name validation rejects blank and oversized names`() {
        assertTrue(ProjectLogic.validateName("  ") != null)
        assertTrue(ProjectLogic.validateName("x".repeat(49)) != null)
        assertFalse(ProjectLogic.validateName("项目 1") != null)
        assertFalse(ProjectLogic.validateName("x".repeat(48)) != null)
    }

    @Test
    fun `create is idempotent on case-insensitive duplicate names`() {
        val existing = listOf(Project("p0", "工作空间", 1L))
        val duplicate = ProjectLogic.create(existing, "工作空间", 100L)
        assertTrue(duplicate.isFailure)
        val ok = ProjectLogic.create(existing, "  新项目  ", 100L)
        assertTrue(ok.isSuccess)
        assertEquals("新项目", ok.getOrThrow().name)
    }

    @Test
    fun `store round-trips projects and current pointer`() {
        val store = ProjectStore(InMemoryPrefs())
        // 默认项目在 init 时自动创建
        assertTrue(store.byId(ProjectLogic.DEFAULT_PROJECT_ID) != null)
        val created = store.create("项目1").getOrThrow()
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, store.currentId())

        assertTrue(store.switchTo(created.id))
        assertEquals(created.id, store.currentId())

        // 切到不存在的项目：显式失败，指针不动（P2）
        assertFalse(store.switchTo("nope"))
        assertEquals(created.id, store.currentId())
    }

    @Test
    fun `stale current pointer falls back to default project`() {
        val store = ProjectStore(InMemoryPrefs())
        // 模拟外部把当前项目指针指向不存在的 id
        val prefsField = store.javaClass.getDeclaredField("prefs")
        prefsField.isAccessible = true
        val prefs = prefsField.get(store) as com.yangz3720.simplywork.testsupport.InMemoryPrefs
        prefs.edit().putString("current_project_id", "ghost").commit()
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, store.currentId())
    }

    @Test
    fun `corrupt store json degrades to empty list and default project survives`() {
        val prefs = com.yangz3720.simplywork.testsupport.InMemoryPrefs()
        prefs.edit().putString("projects", "not json").commit()
        val store = ProjectStore(prefs)
        // ensureDefault 会在损坏数据上重建默认项目（decode 失败 → 空列表 → 补默认）
        assertTrue(store.list().any { it.id == ProjectLogic.DEFAULT_PROJECT_ID })
    }

    @Test
    fun `list serializer keeps working via kotlinx`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(
            ListSerializer(Project.serializer()),
            listOf(Project("a", "A", 5L))
        )
        assertEquals(listOf(Project("a", "A", 5L)), json.decodeFromString(ListSerializer(Project.serializer()), encoded))
    }
}