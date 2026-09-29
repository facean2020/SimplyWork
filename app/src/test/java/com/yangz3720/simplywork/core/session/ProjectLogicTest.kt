package com.yangz3720.simplywork.core.session

import com.yangz3720.simplywork.testsupport.InMemoryPrefs
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Project logic (v1.3): name validation, de-duplication, the default project, switching semantics.
 * The SharedPreferences shell of [ProjectStore] runs the real persistence behaviour against a stub ([InMemoryPrefs]).
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
        // the default project is created automatically at init
        assertTrue(store.byId(ProjectLogic.DEFAULT_PROJECT_ID) != null)
        val created = store.create("项目1").getOrThrow()
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, store.currentId())

        assertTrue(store.switchTo(created.id))
        assertEquals(created.id, store.currentId())

        // switching to a project that does not exist: explicit failure, the pointer does not move (P2)
        assertFalse(store.switchTo("nope"))
        assertEquals(created.id, store.currentId())
    }

    @Test
    fun `stale current pointer falls back to default project`() {
        val store = ProjectStore(InMemoryPrefs())
        // simulate an outsider aiming the current-project pointer at an id that does not exist
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
        // ensureDefault rebuilds the default project on corrupt data (decode fails -> empty list -> default re-added)
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

    // ----- deleting a project (v1.6) -----

    @Test
    fun `delete removes the project and falls a stale current pointer back to default`() {
        val store = ProjectStore(InMemoryPrefs())
        val created = store.create("项目1").getOrThrow()
        assertTrue(store.switchTo(created.id))

        assertTrue(store.delete(created.id).isSuccess)

        assertTrue(store.byId(created.id) == null)
        assertFalse(store.list().any { it.id == created.id })
        // the current project is the one being deleted: the pointer must fall back to the default, never dangle (see currentId)
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, store.currentId())
    }

    @Test
    fun `delete keeps the pointer when another project is current`() {
        val store = ProjectStore(InMemoryPrefs())
        val a = store.create("A").getOrThrow()
        val b = store.create("B").getOrThrow()
        assertTrue(store.switchTo(b.id))

        assertTrue(store.delete(a.id).isSuccess)

        assertEquals(b.id, store.currentId())
        assertTrue(store.byId(b.id) != null)
    }

    @Test
    fun `delete refuses the default project and unknown ids`() {
        val store = ProjectStore(InMemoryPrefs())

        assertTrue(store.delete(ProjectLogic.DEFAULT_PROJECT_ID).isFailure)
        assertTrue(store.delete("ghost").isFailure)
        // a refusal must not be "delete first, talk later": the default project has to still be there
        assertTrue(store.byId(ProjectLogic.DEFAULT_PROJECT_ID) != null)
        assertEquals(ProjectLogic.DEFAULT_PROJECT_ID, store.currentId())
    }

    @Test
    fun `deleted project stays deleted across store instances`() {
        val prefs = InMemoryPrefs()
        val created = ProjectStore(prefs).create("临时").getOrThrow()

        ProjectStore(prefs).delete(created.id).getOrThrow()

        // a fresh instance (i.e. after a restart) reading the same prefs: what was deleted must not come back
        assertFalse(ProjectStore(prefs).list().any { it.id == created.id })
    }
}