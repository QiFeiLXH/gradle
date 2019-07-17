/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.workers.internal


import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.integtests.fixtures.timeout.IntegrationTestTimeout
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.gradle.workers.fixtures.WorkerExecutorFixture
import org.junit.Rule
import spock.lang.IgnoreIf
import spock.lang.Unroll

import static org.gradle.workers.fixtures.WorkerExecutorFixture.ISOLATION_MODES

@IntegrationTestTimeout(120)
@IgnoreIf({ GradleContextualExecuter.parallel })
class WorkerExecutorParallelIntegrationTest extends AbstractWorkerExecutorIntegrationTest {
    @Rule
    BlockingHttpServer blockingHttpServer = new BlockingHttpServer()
    WorkerExecutorFixture.ParameterClass parallelParameterType
    WorkerExecutorFixture.ExecutionClass parallelWorkerExecution
    WorkerExecutorFixture.ExecutionClass failingWorkerExecution
    WorkerExecutorFixture.ExecutionClass alternateParallelWorkerExecution

    def setup() {
        blockingHttpServer.start()

        parallelParameterType = fixture.parameterClass("ParallelParameter", "org.gradle.test").withFields([
                "itemName": "String"
        ])

        parallelWorkerExecution = fixture.executionClass("ParallelWorkerExecution", "org.gradle.test", parallelParameterType)
        parallelWorkerExecution.with {
            imports += ["java.net.URI", "org.gradle.test.FileHelper"]
            extraFields = "private static final String id = UUID.randomUUID().toString()"
            action = """
                System.out.println("Running \${parameters.itemName}...")
                new URI("http", null, "localhost", ${blockingHttpServer.getPort()}, "/\${parameters.itemName}", null, null).toURL().text
                File outputDir = new File("${fixture.outputFileDirPath}")
                File outputFile = new File(outputDir, parameters.itemName)
                FileHelper.write(id, outputFile)
            """
        }

        failingWorkerExecution = fixture.executionClass("FailingWorkerExecution", "org.gradle.test", parallelParameterType)
        failingWorkerExecution.with {
            action = """
                throw new RuntimeException("Failure from \${parameters.itemName}");
            """
        }

        alternateParallelWorkerExecution = fixture.executionClass("AlternateParallelWorkerExecution", "org.gradle.test", parallelParameterType)
        alternateParallelWorkerExecution.with {
            imports += ["java.net.URI", "org.gradle.test.FileHelper"]
            extraFields = "private static final String id = UUID.randomUUID().toString()"
            action = """
                System.out.println("Running alternate_\${parameters.itemName}...")
                new URI("http", null, "localhost", ${blockingHttpServer.getPort()}, "/alternate_\${parameters.itemName}", null, null).toURL().text
            """
        }

        parallelWorkerExecution.writeToBuildFile()
        alternateParallelWorkerExecution.writeToBuildFile()
        withMultipleActionTaskTypeInBuildScript()
    }

    @Unroll
    def "multiple work items can be executed in parallel in #isolationMode (wait for results: #waitForResults)"() {
        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = $isolationMode
                doLast {
                    submitWorkItem("workItem0")
                    submitWorkItem("workItem1")
                    submitWorkItem("workItem2")
                    
                    if (${waitForResults}) {
                        workerExecutor.await()
                    }
                }
            }
        """
        blockingHttpServer.expectConcurrent("workItem0", "workItem1", "workItem2")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")

        where:
        isolationMode           | waitForResults
        'IsolationMode.NONE'    | true
        'IsolationMode.NONE'    | false
        'IsolationMode.PROCESS' | true
        'IsolationMode.PROCESS' | false
        'IsolationMode.CLASSLOADER'  | true
        'IsolationMode.CLASSLOADER'  | false
    }

    @Unroll
    def "multiple work items with different requirements can be executed in parallel in #isolationMode"() {
        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = $isolationMode
                doLast {
                    submitWorkItem("workItem0", ${parallelWorkerExecution.name}.class) { it.classpath.from([ new File("foo") ]) }
                    submitWorkItem("workItem1", ${parallelWorkerExecution.name}.class) { it.classpath.from([ new File("bar") ]) }
                    submitWorkItem("workItem2", ${parallelWorkerExecution.name}.class) { it.classpath.from([ new File("baz") ]) }
                }
            }
        """
        blockingHttpServer.expectConcurrent("workItem0", "workItem1", "workItem2")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")

        where:
        isolationMode << [ "IsolationMode.PROCESS", "IsolationMode.CLASSLOADER" ]
    }

    @Unroll
    def "multiple work items with different actions can be executed in parallel in #isolationMode"() {
        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = $isolationMode
                doLast {
                    submitWorkItem("workItem0", ${alternateParallelWorkerExecution.name}.class)
                    submitWorkItem("workItem1", ${parallelWorkerExecution.name}.class)
                    submitWorkItem("workItem2", ${alternateParallelWorkerExecution.name}.class)
                }
            }
        """
        blockingHttpServer.expectConcurrent("alternate_workItem0", "workItem1", "alternate_workItem2")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")

        where:
        isolationMode << ISOLATION_MODES
    }

    def "a second task action does not start until all work submitted by a previous task action is complete"() {
        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("taskAction1", runnableClass) { isolationMode = IsolationMode.PROCESS } }
                doLast { submitWorkItem("taskAction2", runnableClass) { isolationMode = IsolationMode.CLASSLOADER } }
                doLast { submitWorkItem("taskAction3", runnableClass) { isolationMode = IsolationMode.PROCESS } }
                doLast { submitWorkItem("taskAction4", runnableClass) { isolationMode = IsolationMode.PROCESS } }
                doLast { submitWorkItem("taskAction5", runnableClass) { isolationMode = IsolationMode.CLASSLOADER } }
                doLast { submitWorkItem("taskAction6", runnableClass) { isolationMode = IsolationMode.CLASSLOADER } }
            }
        """
        blockingHttpServer.expectConcurrent("taskAction1")
        blockingHttpServer.expectConcurrent("taskAction2")
        blockingHttpServer.expectConcurrent("taskAction3")
        blockingHttpServer.expectConcurrent("taskAction4")
        blockingHttpServer.expectConcurrent("taskAction5")
        blockingHttpServer.expectConcurrent("taskAction6")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")
    }

    @Unroll
    def "a second task action does not start if work submitted in #isolationMode by a previous task action fails"() {
        failingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("taskAction1", ${parallelWorkerExecution.name}.class) { isolationMode = $isolationMode } }
                doLast { submitWorkItem("taskAction2", ${failingWorkerExecution.name}.class) }
                doLast { submitWorkItem("taskAction3") }
            }
        """
        blockingHttpServer.expectConcurrent("taskAction1")

        expect:
        args("--max-workers=3")
        fails("parallelWorkTask")

        and:
        failureHasCause("A failure occurred while executing ${failingWorkerExecution.name}")
        failureHasCause("Failure from taskAction2")

        where:
        isolationMode << ISOLATION_MODES
    }

    @Unroll
    def "all other submitted work executes when a work item fails in #isolationMode"() {
        failingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("workItem1", ${parallelWorkerExecution.name}.class, IsolationMode.PROCESS)
                    submitWorkItem("workItem2", ${failingWorkerExecution.name}.class, $isolationMode)
                    submitWorkItem("workItem3", ${parallelWorkerExecution.name}.class, IsolationMode.CLASSLOADER)
                }
            }
        """
        blockingHttpServer.expectConcurrent("workItem1", "workItem3")

        expect:
        args("--max-workers=3")
        fails("parallelWorkTask")

        and:
        failureHasCause("A failure occurred while executing ${failingWorkerExecution.name}")
        failureHasCause("Failure from workItem2")

        where:
        isolationMode << ISOLATION_MODES
    }

    @Unroll
    def "all errors are reported when submitting failing work in #isolationModeDescription"() {
        failingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("workItem1", ${failingWorkerExecution.name}.class, $isolationMode1) { config ->
                        config.displayName = "work item 1"
                    }
                    submitWorkItem("workItem2", ${failingWorkerExecution.name}.class, $isolationMode2) { config ->
                        config.displayName = "work item 2"
                    }
                }
            }
        """

        expect:
        args("--max-workers=3")
        fails("parallelWorkTask")

        and:
        failureHasCause("Multiple task action failures occurred")

        and:
        failureHasCause("A failure occurred while executing work item 1")
        failureHasCause("Failure from workItem1")

        and:
        failureHasCause("A failure occurred while executing work item 2")
        failureHasCause("Failure from workItem2")

        where:
        isolationMode1               | isolationMode2               | isolationModeDescription
        'IsolationMode.NONE'         | 'IsolationMode.NONE'         | 'IsolationMode.NONE'
        'IsolationMode.PROCESS'      | 'IsolationMode.PROCESS'      | 'IsolationMode.PROCESS'
        'IsolationMode.CLASSLOADER'  | 'IsolationMode.CLASSLOADER'  | 'IsolationMode.CLASSLOADER'
        'IsolationMode.PROCESS'      | 'IsolationMode.CLASSLOADER'  | 'both IsolationMode.PROCESS and IsolationMode.CLASSLOADER'
        'IsolationMode.NONE'         | 'IsolationMode.CLASSLOADER'  | 'both IsolationMode.NONE and IsolationMode.CLASSLOADER'
    }

    @Unroll
    def "both errors in work items in #isolationMode and errors in the task action are reported"() {
        failingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = $isolationMode
                doLast { 
                    submitWorkItem("workItem1", ${failingWorkerExecution.name}.class) { config ->
                        config.displayName = "work item 1"
                    }
                    throw new RuntimeException("Failure from task action")
                }
            }
        """

        expect:
        args("--max-workers=3")
        fails("parallelWorkTask")

        and:
        failureHasCause("Multiple task action failures occurred")

        and:
        failureHasCause("Failure from task action")

        and:
        failureHasCause("A failure occurred while executing work item 1")
        failureHasCause("Failure from workItem1")

        where:
        isolationMode << ISOLATION_MODES
    }

    @Unroll
    def "user can take responsibility for failing work items in #isolationMode"() {
        failingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            import java.util.concurrent.ExecutionException
            import org.gradle.workers.WorkerExecutionException

            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = $isolationMode
                doLast { 
                    submitWorkItem("workItem1", runnableClass)

                    submitWorkItem("workItem2", ${failingWorkerExecution.name}.class) { config ->
                        config.displayName = "work item 2"
                    }

                    try {
                        workerExecutor.await()
                    } catch (ExecutionException e) {
                        logger.warn e.message
                    } catch (WorkerExecutionException e) {
                        logger.warn e.causes[0].message
                    }
                }
            }
        """
        blockingHttpServer.expectConcurrent("workItem1")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")

        and:
        output.contains("A failure occurred while executing work item 2")

        where:
        isolationMode << ISOLATION_MODES
    }

    def "max workers is honored by parallel work"() {
        def maxWorkers = 3

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast {
                    6.times { i ->
                        submitWorkItem("workItem\${i}")
                    }
                }
            }
        """

        // warm buildSrc
        succeeds("help")

        def calls = ["workItem0", "workItem1", "workItem2", "workItem3", "workItem4", "workItem5"] as String[]
        def handler = blockingHttpServer.expectConcurrentAndBlock(maxWorkers, calls)

        when:
        args("--max-workers=${maxWorkers}")
        executer.withTasks("parallelWorkTask")
        def gradle = executer.start()

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(1)

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(2)

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(3)

        then:
        gradle.waitForFinish()
    }

    def "does not start more than max-workers threads when work items do not submit more work"() {
        def maxWorkers = 3
        def workItems = 200

        given:
        buildFile << """
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast {
                    ${workItems}.times { i ->
                        submitWorkItem("workItem\${i}")
                    }
                }
                doLast {
                    def threadGroup = Thread.currentThread().threadGroup
                    println "\\nWorker Executor threads:"
                    def threads = new Thread[threadGroup.activeCount()]
                    threadGroup.enumerate(threads) 
                    def executorThreads = threads.findAll { it?.name.startsWith("${WorkerExecutionQueueFactory.QUEUE_DISPLAY_NAME}") } 
                    executorThreads.each { println it }
                    
                    // Ensure that we don't leave any threads lying around
                    assert executorThreads.size() <= ${maxWorkers}
                }
            }
        """

        // warm buildSrc
        succeeds("help")

        def calls = []
        workItems.times { i -> calls << "workItem${i}" }
        def handler = blockingHttpServer.expectConcurrentAndBlock(maxWorkers, calls as String[])

        when:
        args("--max-workers=${maxWorkers}")
        executer.withTasks("parallelWorkTask")
        def gradle = executer.start()

        then:
        workItems.times {
            handler.waitForAllPendingCalls()
            handler.release(1)
        }

        then:
        gradle.waitForFinish()
    }

    def "does not start more daemons than max-workers"() {
        def maxWorkers = 3

        given:
        buildFile << """
            import org.gradle.workers.internal.WorkerDaemonClientsManager
            
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = IsolationMode.PROCESS
                doLast {
                    6.times { i ->
                        submitWorkItem("workItem\${i}")
                    }
                }
                doLast {
                    assert services.get(WorkerDaemonClientsManager).allClients.size() == 3
                }
            }
        """

        // warm buildSrc
        succeeds("help")

        def calls = ["workItem0", "workItem1", "workItem2", "workItem3", "workItem4", "workItem5"] as String[]
        def handler = blockingHttpServer.expectConcurrentAndBlock(maxWorkers, calls)

        when:
        args("--max-workers=${maxWorkers}")
        def gradle = executer.withTasks("parallelWorkTask")
                        .requireIsolatedDaemons()
                        .withWorkerDaemonsExpirationDisabled()
                        .start()

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(3)

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(3)

        then:
        handler.waitForAllPendingCalls()

        then:
        gradle.waitForFinish()
    }

    def "work items in the same task reuse daemons"() {
        def maxWorkers = 1

        given:
        buildFile << """
            import org.gradle.workers.internal.WorkerDaemonClientsManager
            
            task parallelWorkTask(type: MultipleWorkItemTask) {
                isolationMode = IsolationMode.PROCESS
                doLast {
                    2.times { i ->
                        submitWorkItem("workItem\${i}")
                    }
                }
            }
        """

        // warm buildSrc
        succeeds("help")

        def calls = ["workItem0", "workItem1"] as String[]
        def handler = blockingHttpServer.expectConcurrentAndBlock(maxWorkers, calls)

        when:
        args("--max-workers=${maxWorkers}")
        executer.withTasks("parallelWorkTask")
        def gradle = executer.withWorkerDaemonsExpirationDisabled().start()

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(1)

        then:
        handler.waitForAllPendingCalls()

        when:
        handler.release(1)

        then:
        handler.waitForAllPendingCalls()

        then:
        gradle.waitForFinish()

        and:
        assertSameDaemonWasUsed("workItem0", "workItem1")
    }

    def "does not start dependent task until all submitted work for current task is complete"() {
        given:
        buildFile << """
            task anotherParallelWorkTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("taskAction1")  
                    submitWorkItem("taskAction2") 
                }
            }
            task parallelWorkTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("taskAction3") }
                
                dependsOn anotherParallelWorkTask
            }
        """
        blockingHttpServer.expectConcurrent("taskAction1", "taskAction2")
        blockingHttpServer.expectConcurrent("taskAction3")

        expect:
        args("--max-workers=3")
        succeeds("parallelWorkTask")
    }

    def "starts dependent task in another project as soon as submitted work for current task is complete (with --parallel)"() {
        given:
        settingsFile << """
            include ':childProject'
        """
        buildFile << """
            task workTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("workTask")
                }
            }
            
            task slowTask {
                doLast { 
                    ${blockingHttpServer.callFromBuild("slowTask1")} 
                    ${blockingHttpServer.callFromBuild("slowTask2")} 
                }
            }
            
            project(':childProject') {
                task dependsOnWorkTask(type: MultipleWorkItemTask) {
                    doLast { submitWorkItem("dependsOnWorkTask") }
                    
                    dependsOn project(':').workTask
                }
            }
        """

        blockingHttpServer.expectConcurrent("workTask", "slowTask1")
        blockingHttpServer.expectConcurrent("dependsOnWorkTask", "slowTask2")

        expect:
        args("--max-workers=3", "--parallel")
        succeeds("dependsOnWorkTask", "slowTask")
    }

    def "can start a non-dependent task when the current task submits async work"() {
        given:
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("task1") }
            }
            
            task secondTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("task2") }
            }
            
            task allTasks {
                dependsOn firstTask, secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1", "task2")

        expect:
        args("--max-workers=2")
        succeeds("allTasks")
    }

    def "can start another task when the current task has multiple actions and submits async work"() {
        given:
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("task1-1") }
                doLast { submitWorkItem("task1-2") }
            }
            
            task secondTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("task2") }
            }
            
            task allTasks {
                dependsOn firstTask, secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1-1", "task2")
        blockingHttpServer.expectConcurrent("task1-2")

        expect:
        args("--max-workers=2")
        succeeds("allTasks")
    }

    def "does not start task in another project when a task action is executing without --parallel"() {
        given:
        settingsFile << """
            include ':childProject'
        """
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { ${blockingHttpServer.callFromBuild("task1")} }
            }
            
            project(':childProject') {
                task secondTask(type: MultipleWorkItemTask) {
                    doLast { submitWorkItem("task2") }
                }
            }
            
            task allTasks {
                dependsOn firstTask, project(':childProject').secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1")
        blockingHttpServer.expectConcurrent("task2")

        expect:
        args("--max-workers=2")
        succeeds("allTasks")
    }

    def "can start task in another project when a task submits async work without --parallel"() {
        given:
        settingsFile << """
            include ':childProject'
        """
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { submitWorkItem("task1") }
            }
            
            project(':childProject') {
                task secondTask(type: MultipleWorkItemTask) {
                    doLast { ${blockingHttpServer.callFromBuild("task2")} }
                }
            }
            
            task allTasks {
                dependsOn firstTask, project(':childProject').secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1", "task2")

        expect:
        args("--max-workers=2")
        succeeds("allTasks")
    }

    def "does not start another task when a task awaits async work"() {
        given:
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("task1-1") 
                    workerExecutor.await()
                    ${blockingHttpServer.callFromBuild("task1-2")}
                }
            }
            
            task secondTask(type: MultipleWorkItemTask) {
                doLast { ${blockingHttpServer.callFromBuild("task2")} }
            }
            
            task allTasks {
                dependsOn firstTask, secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1-1")
        blockingHttpServer.expectConcurrent("task1-2")
        blockingHttpServer.expectConcurrent("task2")

        expect:
        args("--max-workers=3")
        succeeds("allTasks")
    }

    def "does not start task in another project when a task awaits async work"() {
        given:
        settingsFile << """
            include ':childProject'
        """
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("task1-1") 
                    workerExecutor.await()
                    ${blockingHttpServer.callFromBuild("task1-2")}
                }
            }
            
            project(':childProject') {
                task secondTask(type: MultipleWorkItemTask) {
                    doLast { ${blockingHttpServer.callFromBuild("task2")} }
                }
            }
            
            task allTasks {
                dependsOn firstTask, project(':childProject').secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1-1")
        blockingHttpServer.expectConcurrent("task1-2")
        blockingHttpServer.expectConcurrent("task2")

        expect:
        args("--max-workers=3")
        succeeds("allTasks")
    }

    def "can start task in another project when a task awaits async work (with --parallel)"() {
        given:
        settingsFile << """
            include ':childProject'
        """
        buildFile << """
            task firstTask(type: MultipleWorkItemTask) {
                doLast { 
                    submitWorkItem("task1-1") 
                    workerExecutor.await()
                    ${blockingHttpServer.callFromBuild("task1-2")}
                }
            }
            
            project(':childProject') {
                task secondTask(type: MultipleWorkItemTask) {
                    doLast { ${blockingHttpServer.callFromBuild("task2")} }
                }
            }
            
            task allTasks {
                dependsOn firstTask, project(':childProject').secondTask
            }
        """

        blockingHttpServer.expectConcurrent("task1-1", "task2")
        blockingHttpServer.expectConcurrent("task1-2")

        expect:
        args("--max-workers=3", "--parallel")
        succeeds("allTasks")
    }

    def "cannot mutate worker parameters when using IsolationMode.NONE"() {
        def verifyingParameterType = fixture.parameterClass("VerifyingParameter", "org.gradle.test").withFields([
                "itemName": "String",
                "list": "List<String>"
        ])
        def verifyingWorkerExecution = fixture.executionClass("VerifyingWorkerExecution", "org.gradle.test", verifyingParameterType)
        verifyingWorkerExecution.imports += ["java.net.URI"]
        verifyingWorkerExecution.action = """
            assert parameters.list.size() == 1
            new URI("http", null, "localhost", ${blockingHttpServer.getPort()}, "/\${parameters.itemName}", null, null).toURL().text
            assert parameters.list.size() == 1
        """
        verifyingWorkerExecution.writeToBuildFile()

        given:
        buildFile << """
            List<String> mutableList = ["foo"]
            
            class VerifyingRunnableTask extends DefaultTask {
                String item
                List<String> testList
                
                @Inject
                WorkerExecutor getWorkerExecutor() {
                    throw new UnsupportedOperationException()
                }
                
                @TaskAction
                void executeRunnable() {
                    workerExecutor.noIsolation().submit(${verifyingWorkerExecution.name}.class) { 
                        itemName = item.toString()
                        list = testList
                    }
                }
            }
            
            task firstTask(type: VerifyingRunnableTask) {
                item = "task1"
                testList = mutableList
            }
            
            task secondTask(type: MultipleWorkItemTask) {
                doLast { 
                    mutableList.add "bar"
                    assert mutableList.size() == 2
                    submitWorkItem("task2") 
                }
            }
        """

        blockingHttpServer.expectConcurrent("task1", "task2")

        expect:
        args("--max-workers=2")
        succeeds("firstTask", "secondTask")
    }

    String getMultipleActionTaskType() {
        return """
            import javax.inject.Inject
            import org.gradle.workers.WorkerExecutor

            class MultipleWorkItemTask extends DefaultTask {
                def isolationMode = IsolationMode.NONE
                def additionalForkOptions = {}
                def runnableClass = ${parallelWorkerExecution.name}.class
                def additionalClasspath = project.layout.files()

                @Inject
                WorkerExecutor getWorkerExecutor() {
                    throw new UnsupportedOperationException()
                }
                
                def submitWorkItem(String item) {
                    return submitWorkItem(item, runnableClass) 
                }
                
                def submitWorkItem(String item, Class<?> actionClass) {
                    return submitWorkItem(item, actionClass, isolationMode, {})
                }
                
                def submitWorkItem(String item, Class<?> actionClass, IsolationMode isolationMode) {
                    return submitWorkItem(item, actionClass, isolationMode, {})
                }
                
                def submitWorkItem(String item, Class<?> actionClass, Closure configClosure) {
                    return submitWorkItem(item, actionClass, isolationMode, configClosure)
                }
                
                def submitWorkItem(String item, Class<?> actionClass, IsolationMode isolationMode, Closure configClosure) {
                    return workerExecutor."\${getWorkerMethod(isolationMode)}"({ config ->
                        if (config instanceof ProcessWorkerSpec) {
                            config.forkOptions.maxHeapSize = "64m"
                            config.forkOptions(additionalForkOptions)
                        }
                        if (config instanceof ClassLoaderWorkerSpec) {
                            config.classpath.from(additionalClasspath)
                        }
                        configClosure.call(config)
                    }).submit(actionClass) {
                        itemName = item.toString() 
                    }
                }
                
                ${fixture.workerMethodTranslation}
            }
        """
    }

    def withMultipleActionTaskTypeInBuildScript() {
        buildFile << """
            $multipleActionTaskType
        """
    }

    @Override
    void assertSameDaemonWasUsed(String task1, String task2) {
        assert outputFileDir.file(task1).text == outputFileDir.file(task2).text
    }
}
