package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.OutputCapExceededException
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CostSource
import homer.app.models.LlmAccounting
import homer.app.models.OnTimeout
import homer.app.models.RunStop
import homer.app.models.StopReason
import homer.app.models.StopStepsOutcome
import homer.app.models.When
import homer.app.runners.RunDeadline
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [AgentExecutor] tests for graceful stop and run deadlines (ADR-0108). */
class AgentExecutorStopTest : AgentExecutorTestBase() {
    /** One step that times out into a graceful stop, plus the given `onStop:` steps. */
    private fun stoppableAgent(onStop: List<Command> = emptyList()) =
        Agent(
            name = "stoppable",
            commands =
                listOf(
                    Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60, onTimeout = OnTimeout.STOP),
                ),
            onStop = onStop,
        )

    private fun saveReport() = Command.Cmd(cmd = listOf("save"), label = "Save report", timeout = 30)

    @Test
    fun `a step that times out with onTimeout stop runs the onStop steps and records the stop`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                if (contexts.size == 1) throw CancellationException("Timeout reached. 60s.")
                // Long enough that the stop record's `at`, read before the stop steps run, lands
                // a full second before the stop step's `end` on the wall clock.
                withContext(Dispatchers.IO) { Thread.sleep(2_000) }
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            assertThrows<CancellationException> { executor(agent).execute() }

            // The timed-out step, then the one stop step — and no third regular step.
            assertEquals(listOf(false, true), contexts.map { it.onStop })
            assertEquals(30.seconds, contexts[1].interruptGrace)
            assertEquals(
                RunStop(
                    reason = StopReason.STEP_TIMEOUT,
                    stepIndex = 0,
                    stepLabel = "Run tests",
                    at = stops.single().at,
                    stopSteps = StopStepsOutcome.FINISHED,
                ),
                stops.single(),
            )
            assertEquals("step_timeout", executionInfos.first().stoppedBy)
            assertTrue(executionInfos.last().onStop)
            assertEquals(null, executionInfos.last().stoppedBy)
            // The stop is stamped when it was detected, not after the stop steps ran: the
            // record's timeline must not place them before the stop itself.
            assertTrue(
                stops.single().at < executionInfos.last().end,
                "stop.at must be read before the stop steps run",
            )
        }

    @Test
    fun `the stop steps receive the HMR_STOP context and no other step does`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                if (secondArg<ExecutionContext>().onStop) {
                    TestFixtures.mockCommandResult(exitCode = 0)
                } else {
                    throw CancellationException("Timeout reached. 60s.")
                }
            }

            assertThrows<CancellationException> { executor(agent).execute() }

            verify(exactly = 1) {
                mockWorkspace.appendToEnv(
                    match {
                        it[EnvConstants.STOP_REASON] == "step_timeout" &&
                            it[EnvConstants.STOP_STEP_INDEX] == "0" &&
                            it[EnvConstants.STOP_STEP_LABEL] == "Run tests" &&
                            !it.containsKey(EnvConstants.STOP_DEADLINE)
                    },
                )
            }
        }

    @Test
    fun `an interrupted LLM step still records the accounting its executor left behind`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            val accounting = LlmAccounting(sessionId = "s1", costUsd = 0.2, source = CostSource.CLI)
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                val context = secondArg<ExecutionContext>()
                if (context.onStop) {
                    TestFixtures.mockCommandResult(exitCode = 0)
                } else {
                    context.facts.interruptedAccounting = accounting
                    context.facts.cliSessionId = "s1"
                    throw CancellationException("Timeout reached. 60s.")
                }
            }
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs

            assertThrows<CancellationException> { executor(agent).execute() }

            assertEquals(accounting, executionInfos.first().llmAccounting)
            // The interrupted step's CLI session id rides to the stop steps as HMR_STOP_SESSION_ID.
            verify {
                mockWorkspace.appendToEnv(match { it[EnvConstants.STOP_SESSION_ID] == "s1" })
            }
        }

    @Test
    fun `the first failing stop step ends the rest and records the stop as failed`() =
        runTest {
            val agent =
                stoppableAgent(
                    listOf(
                        Command.Cmd(cmd = listOf("a"), label = "First stop step", timeout = 30),
                        Command.Cmd(cmd = listOf("b"), label = "Second stop step", timeout = 30),
                    ),
                )
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                when (contexts.size) {
                    1 -> throw CancellationException("Timeout reached. 60s.")
                    2 -> throw CommandExecutionException(message = "Execution failed", exitCode = 1, command = "a")
                    else -> TestFixtures.mockCommandResult(exitCode = 0)
                }
            }

            assertThrows<CancellationException> { executor(agent).execute() }

            // The interrupted step and the first stop step; the second stop step never ran.
            assertEquals(listOf(false, true), contexts.map { it.onStop })
            assertEquals(StopStepsOutcome.FAILED, stops.single().stopSteps)
        }

    @Test
    fun `an agent with no onStop steps records the stop with no stop steps`() =
        runTest {
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs
            coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("Timeout reached. 60s.")

            assertThrows<CancellationException> { executor(stoppableAgent()).execute() }

            assertEquals(StopStepsOutcome.NONE, stops.single().stopSteps)
            assertEquals(StopReason.STEP_TIMEOUT, stops.single().reason)
        }

    @Test
    fun `an output-cap trip on an onTimeout stop step is an ordinary failure, not a stop`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                throw OutputCapExceededException("Output exceeded the cap")
            }

            assertThrows<OutputCapExceededException> { executor(agent).execute() }

            // The cap is an ordinary failure even on a step declaring `onTimeout: stop`: the step
            // ran, no stop step followed it and no stop was recorded.
            assertEquals(listOf(false), contexts.map { it.onStop })
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
            verify(exactly = 0) { mockWorkspace.appendToEnv(match { it.containsKey(EnvConstants.STOP_REASON) }) }
        }

    @Test
    fun `a timeout of a step that did not opt in stops nothing and records no stop`() =
        runTest {
            val agent =
                Agent(
                    name = "plain",
                    commands = listOf(Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60)),
                    onStop = listOf(saveReport()),
                )
            coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("Timeout reached. 60s.")

            assertThrows<CancellationException> { executor(agent).execute() }

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
            // The stop context is not leaked into a run that never stopped.
            verify(exactly = 0) { mockWorkspace.appendToEnv(match { it.containsKey(EnvConstants.STOP_REASON) }) }
        }

    @Test
    fun `an ordinary step failure records no stop`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            coEvery { mockExecutor.execute(any(), any(), any()) } throws
                CommandExecutionException(message = "Execution failed", exitCode = 1, command = "run")

            assertThrows<CommandExecutionException> { executor(agent).execute() }

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
        }

    @Test
    fun `a soft run deadline stops the run and names run_deadline`() =
        runTest {
            val soft = System.currentTimeMillis() / 1000 + 2
            val hard = soft + 3600
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = soft, hardEpochSec = hard)
            val agent = stoppableAgent(listOf(saveReport()))
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                if (contexts.size == 1) {
                    // The deadline is read off the wall clock, so the step has to outlive it in real time.
                    withContext(Dispatchers.IO) { Thread.sleep(2_500) }
                    throw CancellationException("Timeout reached. 1s.")
                }
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            assertThrows<CancellationException> { executor(agent).execute() }

            assertEquals(StopReason.RUN_DEADLINE, stops.single().reason)
            assertEquals(0, stops.single().stepIndex)
            verify {
                mockWorkspace.appendToEnv(
                    match {
                        it[EnvConstants.STOP_REASON] == "run_deadline" && it[EnvConstants.STOP_DEADLINE] == hard.toString()
                    },
                )
            }
        }

    @Test
    fun `a stop between steps names the step that completed after the deadline passed`() =
        runTest {
            val soft = System.currentTimeMillis() / 1000 + 2
            val hard = soft + 3600
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = soft, hardEpochSec = hard)
            val agent =
                Agent(
                    name = "stoppable",
                    commands =
                        listOf(
                            Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60),
                            Command.Cmd(cmd = listOf("next"), label = "Next step", timeout = 60),
                        ),
                    onStop = listOf(saveReport()),
                )
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                if (contexts.size == 1) {
                    // The deadline is read off the wall clock, so the step has to outlive it in real
                    // time. The executor caps the step's timeout but does not wrap it in withTimeout,
                    // so a command can finish after the cap has passed.
                    withContext(Dispatchers.IO) { Thread.sleep(2_500) }
                }
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            assertThrows<CancellationException> { executor(agent).execute() }

            // The stop names the step whose turn came last — the one that ran to success, not the
            // one the deadline pre-empted — and its own row was never interrupted.
            assertEquals(StopReason.RUN_DEADLINE, stops.single().reason)
            assertEquals(0, stops.single().stepIndex)
            assertEquals("Run tests", stops.single().stepLabel)
            assertEquals(false, executionInfos.first().skipped)
            assertEquals(null, executionInfos.first().stoppedBy)
            verify {
                mockWorkspace.appendToEnv(
                    match {
                        it[EnvConstants.STOP_STEP_INDEX] == "0" && it[EnvConstants.STOP_STEP_LABEL] == "Run tests"
                    },
                )
            }
        }

    @Test
    fun `a stop between steps names a when-skipped step too, and its row carries no stoppedBy`() =
        runTest {
            val soft = System.currentTimeMillis() / 1000 + 2
            val hard = soft + 3600
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = soft, hardEpochSec = hard)
            val agent =
                Agent(
                    name = "stoppable",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("run"),
                                label = "Run tests",
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("false-expr")),
                            ),
                            Command.Cmd(cmd = listOf("next"), label = "Next step", timeout = 60),
                        ),
                    onStop = listOf(saveReport()),
                )
            // The `when:` predicate evaluates false through the shell — and the evaluation itself
            // outlives the soft deadline, which is read again before the next step starts.
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                Thread.sleep(2_500)
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs

            assertThrows<CancellationException> { executor(agent).execute() }

            // The record names the step whose turn came last — the one `when:` skipped. It never
            // started, so its row is a plain skip and carries no stoppedBy; the next step never ran.
            assertEquals(StopReason.RUN_DEADLINE, stops.single().reason)
            assertEquals(0, stops.single().stepIndex)
            assertEquals("Run tests", stops.single().stepLabel)
            assertEquals(true, executionInfos.first().skipped)
            assertEquals(null, executionInfos.first().stoppedBy)
            verify {
                mockWorkspace.appendToEnv(
                    match {
                        it[EnvConstants.STOP_STEP_INDEX] == "0" && it[EnvConstants.STOP_STEP_LABEL] == "Run tests"
                    },
                )
            }
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `a soft run deadline that passes before the first step starts no stop phase`() =
        runTest {
            every { mockSession.runDeadline } returns
                RunDeadline(softEpochSec = System.currentTimeMillis() / 1000 - 1, hardEpochSec = Long.MAX_VALUE)
            val agent = stoppableAgent(listOf(saveReport()))

            assertThrows<CancellationException> { executor(agent).execute() }

            // Nothing was interrupted, so there is nothing to stop: no command ran and no stop was recorded.
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
        }

    @Test
    fun `a stop step's timeout is capped ten seconds under the hard deadline`() =
        runTest {
            val now = System.currentTimeMillis() / 1000
            val hard = now + 300
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = now + 250, hardEpochSec = hard)
            // A declared timeout well past the hard deadline, so only the cap can produce the value seen.
            val agent = stoppableAgent(listOf(Command.Cmd(cmd = listOf("save"), label = "Save report", timeout = 600)))
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                if (contexts.size == 1) throw CancellationException("Timeout reached. 60s.")
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            val before = System.currentTimeMillis() / 1000
            assertThrows<CancellationException> { executor(agent).execute() }
            val after = System.currentTimeMillis() / 1000

            // The hard deadline is the backend's hard kill, so the stop step must finish ten
            // seconds before it — the margin, measured against the clock the stop phase read.
            val capped = contexts[1].timeoutOverrideSeconds!!
            assertTrue(
                capped in (hard - 10 - after)..(hard - 10 - before),
                "stop step timeout $capped is not hard - 10s - now ($hard)",
            )
            assertTrue(capped < 600, "the declared timeout was not cut: $capped")
        }

    @Test
    fun `a regular step's timeout is capped at the soft run deadline and still granted the interrupt grace`() =
        runTest {
            val now = System.currentTimeMillis() / 1000
            val soft = now + 30
            val hard = soft + 7200
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = soft, hardEpochSec = hard)
            // No onTimeout: stop — only the published deadline puts a grace on a plain step.
            val agent = Agent(name = "stoppable", commands = listOf(Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60)))
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            val before = System.currentTimeMillis() / 1000
            executor(agent).execute()
            val after = System.currentTimeMillis() / 1000

            val capped = contexts.single().timeoutOverrideSeconds!!
            assertTrue(
                capped in (soft - after)..(soft - before),
                "regular step timeout $capped is not the soft deadline minus now ($soft)",
            )
            assertTrue(capped < 60, "the declared timeout was not cut: $capped")
            assertEquals(
                30.seconds,
                contexts.single().interruptGrace,
                "a step under a published deadline runs under a grace without opting in",
            )
        }

    @Test
    fun `a regular step's timeout is capped ten seconds under the hard deadline`() =
        runTest {
            val now = System.currentTimeMillis() / 1000
            val hard = now + 40
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = now + 3600, hardEpochSec = hard)
            val agent = Agent(name = "stoppable", commands = listOf(Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60)))
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            val before = System.currentTimeMillis() / 1000
            executor(agent).execute()
            val after = System.currentTimeMillis() / 1000

            val capped = contexts.single().timeoutOverrideSeconds!!
            assertTrue(
                capped in (hard - 10 - after)..(hard - 10 - before),
                "regular step timeout $capped is not hard - 10s - now ($hard)",
            )
            assertTrue(capped < 60, "the declared timeout was not cut: $capped")
        }

    @Test
    fun `a deadline remainder under a second is floored at one second`() =
        runTest {
            val now = System.currentTimeMillis() / 1000
            // hard - 10 lands 0..1 s after the plan reads the clock, so the raw cap is 1 or 0.
            every { mockSession.runDeadline } returns RunDeadline(softEpochSec = now + 3600, hardEpochSec = now + 11)
            val agent = Agent(name = "stoppable", commands = listOf(Command.Cmd(cmd = listOf("run"), label = "Run tests", timeout = 60)))
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            executor(agent).execute()

            val capped = contexts.single().timeoutOverrideSeconds!!
            assertEquals(1L, capped, "a sub-second remainder must be floored at one second, not pass a zero timeout")
        }

    @Test
    fun `a stop step that times out ends the stop without a second stop sequence`() =
        runTest {
            val agent =
                stoppableAgent(
                    listOf(
                        Command.Cmd(cmd = listOf("a"), label = "First stop step", timeout = 30),
                        Command.Cmd(cmd = listOf("b"), label = "Second stop step", timeout = 30),
                    ),
                )
            val contexts = mutableListOf<ExecutionContext>()
            coEvery { mockExecutor.execute(any(), capture(contexts), any()) } coAnswers {
                // The interrupted step and the first stop step both time out; the second stop step
                // must never start, and the timeout of a stop step is not a second stop trigger.
                if (contexts.size <= 2) throw CancellationException("Timeout reached. 30s.")
                TestFixtures.mockCommandResult(exitCode = 0)
            }
            val stops = mutableListOf<RunStop>()
            coEvery { mockProcessManager.setStop(any(), capture(stops)) } just Runs

            assertThrows<CancellationException> { executor(agent).execute() }

            assertEquals(listOf(false, true), contexts.map { it.onStop })
            assertEquals(StopStepsOutcome.FAILED, stops.single().stopSteps)
            coVerify(exactly = 1) { mockProcessManager.setStop(any(), any()) }
        }

    @Test
    fun `a kill during the run runs no stop steps`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            val started = CompletableDeferred<Unit>()
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            val job = launch { runCatching { executor(agent).execute() } }
            started.await()
            job.cancelAndJoin()

            // A kill is not a stop: the interrupted step's CancellationException reaches the catch
            // with the run's own job already cancelled, so no stop phase runs.
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `a kill during the stop steps leaves no stop record`() =
        runTest {
            val agent = stoppableAgent(listOf(saveReport()))
            val inStopStep = CompletableDeferred<Unit>()
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                if (secondArg<ExecutionContext>().onStop) {
                    inStopStep.complete(Unit)
                    delay(Long.MAX_VALUE)
                    TestFixtures.mockCommandResult(exitCode = 0)
                }
                throw CancellationException("Timeout reached. 60s.")
            }

            val job = launch { runCatching { executor(agent).execute() } }
            inStopStep.await()
            job.cancelAndJoin()

            // The stop phase was left by the kill, so the run records nothing about a stop it
            // never finished — same as any other kill.
            coVerify(exactly = 0) { mockProcessManager.setStop(any(), any()) }
        }
}
