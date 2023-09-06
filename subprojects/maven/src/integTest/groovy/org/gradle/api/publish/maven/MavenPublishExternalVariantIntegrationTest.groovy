/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.api.publish.maven

import groovy.test.NotYetImplemented
import org.gradle.integtests.fixtures.publish.maven.AbstractMavenPublishIntegTest

class MavenPublishExternalVariantIntegrationTest extends AbstractMavenPublishIntegTest {

    def "publishes resolved jvm coordinates for multi-coordinates external module dependency"() {
        given:
        buildFile << """
            plugins {
                id 'java-library'
                id 'maven-publish'
            }
            ${mavenCentralRepository()}
            dependencies {
                implementation "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.2"
            }

            interface MyServices {
                @Inject
                SoftwareComponentFactory getSoftwareComponentFactory()
            }
            def factory = objects.newInstance(MyServices).softwareComponentFactory
            def comp = factory.adhoc("comp")
            comp.addVariantsFromConfiguration(configurations.runtimeElements) {
                mapToMavenScope('compile')
                dependencyMapping {
                    publishResolvedCoordinates = true
                    resolutionConfiguration = configurations.runtimeClasspath
                }
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    maven(MavenPublication) {
                        groupId = "org"
                        artifactId = "runtimeElements"
                        version = "1.0"
                        from comp
                    }
                }
            }
        """
        def repoModule = javaLibrary(mavenRepo.module('org', "runtimeElements", '1.0'))

        when:
        succeeds "publish"

        then:
        // POM uses resolved variant coordinates
        def dependencies = repoModule.parsedPom.scopes.compile.dependencies
        dependencies.size() == 1
        def dependency = dependencies.values().first()
        dependency.groupId == "org.jetbrains.kotlinx"
        dependency.artifactId == "kotlinx-coroutines-core-jvm"
        dependency.version == "1.7.2"

        // GMM continues to use component coordinates
        def gmmDependencies = repoModule.parsedModuleMetadata.variant("runtimeElements").dependencies
        gmmDependencies.size() == 1
        def gmmDependency = gmmDependencies.first()
        gmmDependency.group == "org.jetbrains.kotlinx"
        gmmDependency.module == "kotlinx-coroutines-core"
        gmmDependency.version == "1.7.2"
    }

    def "publishes resolved child coordinates for multi-coordinate project dependency"() {
        given:
        settingsFile << """
            include 'other'
            rootProject.name = 'root'
        """
        file("other/build.gradle") << """
            plugins {
                id 'maven-publish'
            }
            ${publishMultiCoordinateComponent(otherSeparateConfigurations)}
        """

        buildFile << """
            plugins {
                id 'maven-publish'
            }
            ${publishMultiCoordinateComponent(rootSeparateConfigurations)}

            dependencies {
                primaryImplementation project(':other')
                secondaryImplementation project(':other')
            }
        """

        when:
        [rootSeparateConfigurations, otherSeparateConfigurations].count(true).times {
            executer.expectDocumentedDeprecationWarning("Calling configuration method 'attributes(Action)' is deprecated for configuration 'primaryRuntimeElements-published', which has permitted usage(s):\n" +
                "\tDeclarable - this configuration can have dependencies added to it\n" +
                "This method is only meant to be called on configurations which allow the (non-deprecated) usage(s): 'Consumable, Resolvable'. This behavior has been deprecated. This behavior is scheduled to be removed in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#deprecated_configuration_usage")
            executer.expectDocumentedDeprecationWarning("Calling configuration method 'attributes(Action)' is deprecated for configuration 'secondaryRuntimeElements-published', which has permitted usage(s):\n" +
                "\tDeclarable - this configuration can have dependencies added to it\n" +
                "This method is only meant to be called on configurations which allow the (non-deprecated) usage(s): 'Consumable, Resolvable'. This behavior has been deprecated. This behavior is scheduled to be removed in Gradle 9.0. Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_8.html#deprecated_configuration_usage")
        }
        succeeds(":publish")

        then:
        def root = javaLibrary(mavenRepo.module('org', 'root', '1.0'))
        def primary = javaLibrary(mavenRepo.module('org', 'root-primary', '1.0'))
        def secondary = javaLibrary(mavenRepo.module('org', 'root-secondary', '1.0'))

        !root.parsedPom.scopes.compile
        !root.parsedPom.scopes.runtime

        def primaryDeps = primary.parsedPom.scopes.compile.dependencies
        primaryDeps.size() == 1
        def primaryDep = primaryDeps.values().first()
        primaryDep.groupId == "org"
        primaryDep.artifactId == "other-primary"
        primaryDep.version == "1.0"

        def secondaryDeps = secondary.parsedPom.scopes.compile.dependencies
        secondaryDeps.size() == 1
        def secondaryDep = secondaryDeps.values().first()
        secondaryDep.groupId == "org"
        secondaryDep.artifactId == "other-secondary"
        secondaryDep.version == "1.0"

        where:
        rootSeparateConfigurations | otherSeparateConfigurations
        false                      | false
        false                      | true
        true                       | false
        true                       | true
    }

    // Implementing this would require performing artifact resolution while publishing.
    // We should do this eventually.
    @NotYetImplemented
    def "publishes classifier of maven-incompatible dependency"() {
        given:
        settingsFile << """
            include 'other'
            rootProject.name = 'root'
        """
        file("other/build.gradle") << """
            plugins {
                id 'maven-publish'
            }
            ${publishMavenIncompatibleComponent()}
        """

        buildFile << """
            plugins {
                id 'maven-publish'
            }
            ${publishMultiCoordinateComponent()}

            dependencies {
                primaryImplementation project(':other')
                secondaryImplementation project(':other')
            }
        """

        when:
        succeeds(":publish")

        then:
        def root = javaLibrary(mavenRepo.module('org', 'root', '1.0'))
        def primary = javaLibrary(mavenRepo.module('org', 'root-primary', '1.0'))
        def secondary = javaLibrary(mavenRepo.module('org', 'root-secondary', '1.0'))

        !root.parsedPom.scopes.compile
        !root.parsedPom.scopes.runtime

        def primaryDeps = primary.parsedPom.scopes.compile.dependencies
        primaryDeps.size() == 1
        def primaryDep = primaryDeps.values().first()
        primaryDep.groupId == "org"
        primaryDep.artifactId == "other"
        primaryDep.version == "1.0"
        primaryDep.classifier == "primary"

        def secondaryDeps = secondary.parsedPom.scopes.compile.dependencies
        secondaryDeps.size() == 1
        def secondaryDep = secondaryDeps.values().first()
        secondaryDep.groupId == "org"
        secondaryDep.artifactId == "other"
        secondaryDep.version == "1.0"
        secondaryDep.classifier == "secondary"
    }

    def publishMultiCoordinateComponent(boolean withSeparatePublishedConfigurations = false) {
        """
            configurations {
                dependencyScope("primaryImplementation")
                consumable("primaryRuntimeElements") {
                    extendsFrom(primaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "primary"))
                    }
                }
                ${withSeparatePublishedConfigurations ? """
                    create("primaryRuntimeElements-published") {
                        canBeConsumed = false
                        canBeResolved = false
                        extendsFrom(primaryImplementation)
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "primary"))
                        }
                    }
                    """ : ""
                }
                resolvable("primaryRuntimeClasspath") {
                    extendsFrom(primaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "primary"))
                    }
                }

                dependencyScope("secondaryImplementation")
                consumable("secondaryRuntimeElements") {
                    extendsFrom(secondaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "secondary"))
                    }
                }
                ${withSeparatePublishedConfigurations ? """
                    create("secondaryRuntimeElements-published") {
                        canBeConsumed = false
                        canBeResolved = false
                        extendsFrom(secondaryImplementation)
                        attributes {
                            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "secondary"))
                        }
                    }
                    """ : ""
                }
                resolvable("secondaryRuntimeClasspath") {
                    extendsFrom(secondaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "secondary"))
                    }
                }
            }

            interface MyServices {
                @Inject
                SoftwareComponentFactory getSoftwareComponentFactory()
            }
            def factory = objects.newInstance(MyServices).softwareComponentFactory

            def primary = factory.adhoc("primary")
            primary.addVariantsFromConfiguration(configurations."${withSeparatePublishedConfigurations ?
                "primaryRuntimeElements-published" : "primaryRuntimeElements"
            }") {
                mapToMavenScope('compile')
                dependencyMapping {
                    publishResolvedCoordinates = true
                    resolutionConfiguration = configurations.primaryRuntimeClasspath
                }
            }

            def secondary = factory.adhoc("secondary")
            secondary.addVariantsFromConfiguration(configurations."${withSeparatePublishedConfigurations ?
                "secondaryRuntimeElements-published" : "secondaryRuntimeElements"
            }") {
                mapToMavenScope('compile')
                dependencyMapping {
                    publishResolvedCoordinates = true
                    resolutionConfiguration = configurations.secondaryRuntimeClasspath
                }
            }

            abstract class RootComponent implements ComponentWithVariants, org.gradle.api.internal.component.SoftwareComponentInternal {
                private final String name;

                @Inject
                public RootComponent(String name) {
                    this.name = name;
                }

                @Override
                public String getName() {
                    return name;
                }

                @Override
                Set<SoftwareComponentVariant> getUsages() {
                    return Collections.emptySet();
                }

                @Override
                abstract NamedDomainObjectContainer<SoftwareComponent> getVariants();
            }

            def root = project.objects.newInstance(RootComponent, "root")
            root.variants.addAll([primary, secondary])

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    rootPub(MavenPublication) {
                        groupId = "org"
                        artifactId = project.name
                        version = "1.0"
                        from root
                    }
                    primaryPub(MavenPublication) {
                        groupId = "org"
                        artifactId = project.name + "-primary"
                        version = "1.0"
                        from primary
                    }
                    secondaryPub(MavenPublication) {
                        groupId = "org"
                        artifactId = project.name + "-secondary"
                        version = "1.0"
                        from secondary
                    }
                }
            }
        """
    }

    def publishMavenIncompatibleComponent() {
        """
            configurations {
                dependencyScope("primaryImplementation")
                consumable("primaryElements") {
                    extendsFrom(primaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "primary"))
                    }
                    outgoing {
                        artifact(project.file("primary.jar")) {
                            classifier = "primary"
                        }
                    }
                }
                resolvable("primaryRuntimeClasspath") {
                    extendsFrom(primaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "primary"))
                    }
                }

                dependencyScope("secondaryImplementation")
                consumable("secondaryElements") {
                    extendsFrom(secondaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "secondary"))
                    }
                    outgoing {
                        artifact(project.file("secondary.jar")) {
                            classifier = "secondary"
                        }
                    }
                }
                resolvable("secondaryRuntimeClasspath") {
                    extendsFrom(secondaryImplementation)
                    attributes {
                        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category, "secondary"))
                    }
                }
            }

            interface MyServices {
                @Inject
                SoftwareComponentFactory getSoftwareComponentFactory()
            }
            def factory = objects.newInstance(MyServices).softwareComponentFactory

            def root = factory.adhoc("root")
            root.addVariantsFromConfiguration(configurations.primaryElements) {
                mapToMavenScope('compile')
                dependencyMapping {
                    publishResolvedCoordinates = true
                    resolutionConfiguration = configurations.primaryRuntimeClasspath
                }
            }

            def secondary = factory.adhoc("secondary")
            root.addVariantsFromConfiguration(configurations.secondaryElements) {
                mapToMavenScope('compile')
                mapToOptional()
                dependencyMapping {
                    publishResolvedCoordinates = true
                    resolutionConfiguration = configurations.secondaryRuntimeClasspath
                }
            }

            publishing {
                repositories {
                    maven { url "${mavenRepo.uri}" }
                }
                publications {
                    rootPub(MavenPublication) {
                        groupId = "org"
                        artifactId = project.name
                        version = "1.0"
                        from root
                    }
                }
            }
        """
    }

    def test() {
        mavenRepo.module("org", "foo").publish()
        mavenRepo.module("org", "bar").publish()
        buildFile << """
            configurations {
                foo
            }
            dependencies {
                foo "org:bar:1.0"
                constraints {
                    foo "org:foo:1.0"
                }
            }
            task resolve {
                def rootProvider = configurations.foo.incoming.resolutionResult.rootComponent
                doLast {
                    def root = rootProvider.get()
                    println root.dependencies.size()
                    println root.dependents.size()
                    println root.selectionReason
                    println root.moduleVersion
                    println root.variants.size()
                    println root.id
                }
            }
        """

        expect:
        succeeds("resolve")
    }
}
