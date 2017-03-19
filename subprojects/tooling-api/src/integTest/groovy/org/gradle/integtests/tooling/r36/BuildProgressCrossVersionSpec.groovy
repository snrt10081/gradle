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

package org.gradle.integtests.tooling.r36

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.build.BuildEnvironment

@ToolingApiVersion(">=2.5")
@TargetGradleVersion(">=3.6")
class BuildProgressCrossVersionSpec extends ToolingApiSpecification {

    def "when running a build then root build progress event is 'Run build'"() {
        when:
        def events = new ProgressEvents()
        withConnection { ProjectConnection connection ->
            connection.newBuild()
                .addProgressListener(events)
                .run()
        }

        then:
        events.assertIsABuild()
    }

    def "when running build action then root build progress event is 'Run build'"() {
        when:
        def events = new ProgressEvents()
        withConnection { ProjectConnection connection ->
            def runner = connection.action(new SomeBuildAction())
            runner.addProgressListener(events)
            runner.run()
        }

        then:
        events.assertIsABuild()
    }

    static class SomeBuildAction implements BuildAction<BuildEnvironment> {
        @Override
        BuildEnvironment execute(BuildController controller) {
            return controller.getModel(BuildEnvironment)
        }
    }

    def "generates events for buildSrc builds"() {
        given:
        buildSrc()
        javaProjectWithTests()

        when:
        def events = new ProgressEvents()
        withConnection { ProjectConnection connection ->
            connection.newBuild()
                .addProgressListener(events)
                .run()
        }

        then:
        events.assertIsABuild()

        def buildSrc = events.operation("Build buildSrc")
        def runBuildSrc = buildSrc.child('Run build')
        def configureBuildSrc = runBuildSrc.child("Configure build")
        configureBuildSrc.child("Configure project :buildSrc")
        configureBuildSrc.child("Configure project :buildSrc:a")
        configureBuildSrc.child("Configure project :buildSrc:b")

        def buildSrcTasks = runBuildSrc.child("Run tasks")

        def buildSrcCompileJava = buildSrcTasks.child("Task :buildSrc:compileJava")
        buildSrcCompileJava.descriptor.name == ':buildSrc:compileJava'
        buildSrcCompileJava.descriptor.taskPath == ':buildSrc:compileJava'

        buildSrcTasks.child("Task :buildSrc:a:compileJava").child("Resolve dependencies :buildSrc:a:compileClasspath")
        buildSrcTasks.child("Task :buildSrc:b:compileJava").child("Resolve dependencies :buildSrc:b:compileClasspath")

        buildSrcTasks.child("Task :buildSrc:a:test").descendant("Gradle Test Run :buildSrc:a:test")
        buildSrcTasks.child("Task :buildSrc:b:test")

        when:
        events.clear()
        withConnection { ProjectConnection connection ->
            connection.newBuild()
                .addProgressListener(events, [OperationType.TASK] as Set)
                .forTasks("build")
                .run()
        }

        then:
        events.tasks.size() == events.operations.size()
        events.operation("Task :buildSrc:a:compileJava")
        events.operation("Task :buildSrc:a:test")
        events.operation("Task :compileJava")
        events.operation("Task :test")

        when:
        events.clear()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild()
                    .addProgressListener(events, [OperationType.TEST] as Set)
                    .withArguments("--rerun-tasks")
                    .forTasks("build")
                    .run()
        }

        then:
        events.tests.size() == events.operations.size()
        events.operation("Gradle Test Run :buildSrc:a:test")
        events.operation("Test ok(Test)")
        events.operation("Gradle Test Run :test")
    }

    def javaProjectWithTests() {
        buildFile << """
            allprojects { 
                apply plugin: 'java'
                repositories { mavenCentral() }
                dependencies { testCompile 'junit:junit:4.12' }
            }
"""
        file("src/main/java/Thing.java") << """class Thing { }"""
        file("src/test/java/ThingTest.java") << """
            public class ThingTest { 
                @org.junit.Test
                public void ok() { }
            }
        """
    }

    def buildSrc() {
        file("buildSrc/settings.gradle") << "include 'a', 'b'"
        file("buildSrc/build.gradle") << """
            allprojects {   
                apply plugin: 'java'
                repositories { mavenCentral() }
                dependencies { testCompile 'junit:junit:4.12' }
            }
            dependencies {
                compile project(':a')
                compile project(':b')
            }
"""
        file("buildSrc/a/src/main/java/A.java") << "public class A {}"
        file("buildSrc/a/src/test/java/Test.java") << "public class Test { @org.junit.Test public void ok() { } }"
        file("buildSrc/b/src/main/java/B.java") << "public class B {}"
        file("buildSrc/b/src/test/java/Test.java") << "public class Test { @org.junit.Test public void ok() { } }"
    }
}
