package com.jay.fxi.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a purge takes, what it leaves standing, and what it admits it could not do.
 *
 * The names are a decision recorded elsewhere; this is about the deleting. Taking too little leaves
 * a readable copy of what was meant to be gone, taking too much deletes a live store, and reporting
 * a refusal as a clean sweep hides the one outcome worth knowing about — all three silent, since
 * nothing reads a preference file until the screen that needs it opens.
 */
class RetiredStoresTest {

    @get:Rule val folder = TemporaryFolder()

    private fun write(name: String) = File(folder.root, name).also { it.writeText("x") }

    @Test
    fun aRetiredStoreGoesWithItsTempSiblings() {
        val main = write("fxi_bank_preferences.preferences_pb")
        val tmp = write("fxi_bank_preferences.preferences_pb.tmp")
        val lock = write("fxi_bank_preferences.preferences_pb.lock")

        val sweep = RetiredStores.purge(folder.root)

        assertEquals(setOf(main.name, tmp.name, lock.name), sweep.deleted.toSet())
        assertEquals(emptyList<String>(), sweep.failed)
        listOf(main, tmp, lock).forEach { assertTrue("${it.name} 이 남았다", !it.exists()) }
    }

    /**
     * A store that is not retired is not touched, and neither is one whose name merely starts the
     * same way. `fxi_bank_preferences_v2.preferences_pb` is a different store, not a temp file.
     */
    @Test
    fun everythingElseIsLeftAlone() {
        write("fxi_bank_preferences.preferences_pb")
        val live = write("fxi_user_intent.preferences_pb")
        val epoch = write("fxi_access_epoch.preferences_pb")
        val lookalike = write("fxi_bank_preferences_v2.preferences_pb")

        val sweep = RetiredStores.purge(folder.root)

        assertEquals(listOf("fxi_bank_preferences.preferences_pb"), sweep.deleted)
        listOf(live, epoch, lookalike).forEach { assertTrue("${it.name} 이 지워졌다", it.exists()) }
    }

    /**
     * Having nothing to do is the ordinary case, not a failure.
     *
     * This runs on every start, so all but the first find the work already done — and a fresh
     * install has no `datastore` directory at all until something writes one. Neither may be
     * reported as a problem, or the warning that means something would be lost among them.
     */
    @Test
    fun aSecondRunAndAMissingDirectoryBothDoNothing() {
        write("fxi_bank_preferences.preferences_pb")
        assertEquals(1, RetiredStores.purge(folder.root).deleted.size)

        listOf(RetiredStores.purge(folder.root), RetiredStores.purge(File(folder.root, "never-made")))
            .forEach {
                assertEquals(emptyList<String>(), it.deleted)
                assertEquals(emptyList<String>(), it.failed)
                assertNull("없는 것과 못 읽는 것을 같이 취급했다", it.unreadableDir)
            }
    }

    /**
     * A refusal is named, not dropped.
     *
     * `File.delete` answers `false` and throws nothing, so a sweep that only collected successes
     * would report "nothing to delete" for a phone whose v1 file is still sitting there — the same
     * answer as a phone that is already clean. Made to fail by taking write permission off the
     * directory, which is what removing a name from it requires.
     */
    @Test
    fun aFileThatWillNotDeleteComesBackNamed() {
        val stubborn = write("fxi_bank_preferences.preferences_pb")
        folder.root.setWritable(false)
        try {
            val sweep = RetiredStores.purge(folder.root)
            assertEquals(
                "삭제가 거부됐는데 보고되지 않았다 (또는 이 환경이 디렉터리 권한으로 삭제를 막지 못한다)",
                listOf(stubborn.name), sweep.failed
            )
            assertEquals(emptyList<String>(), sweep.deleted)
        } finally {
            folder.root.setWritable(true)
        }
    }

    /**
     * A path that is there but will not enumerate is reported, and is not the same as absence.
     *
     * `listFiles` answers `null` for both "not a directory" and "the filesystem said no", and
     * neither swept anything. Folding them into the empty answer would mean a phone that cannot be
     * cleaned looks exactly like one that needs no cleaning.
     */
    @Test
    fun aDirectoryThatWillNotListIsReported() {
        val notADirectory = write("datastore")
        val sweep = RetiredStores.purge(notADirectory)
        assertEquals(notADirectory.path, sweep.unreadableDir)
        assertEquals(emptyList<String>(), sweep.deleted)
    }
}
