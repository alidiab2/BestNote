package app.bestnote.ai.scan.bench

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** `BENCH_PREPARE=1`: writes the boards and the two stacked photos sent to the AI to build/bench/. */
class BenchPrepare {
    @Test fun prepare() {
        assumeTrue(System.getenv("BENCH_PREPARE") != null)
        val dir = File("build/bench")
        for (n in BenchBoards.names) BenchBoards.write(BenchBoards.board(n), File(dir, "$n.png"))
        BenchBoards.write(BenchBoards.stack("board2", "board8"), File(dir, "stack-2-8.jpg"))
        BenchBoards.write(BenchBoards.stack("board3", "board6"), File(dir, "stack-3-6.jpg"))
        println(BenchBoards.stackOffsets("board2", "board8"))
        println(BenchBoards.stackOffsets("board3", "board6"))
    }
}
