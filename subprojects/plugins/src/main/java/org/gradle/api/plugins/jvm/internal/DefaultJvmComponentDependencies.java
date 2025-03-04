/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.api.plugins.jvm.internal;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependencyBundle;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.internal.artifacts.dsl.dependencies.DependencyFactory;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.jvm.JvmComponentDependencies;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderConvertible;
import org.gradle.internal.Cast;
import org.gradle.internal.component.external.model.ImmutableCapability;
import org.gradle.internal.component.external.model.ProjectTestFixtures;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.gradle.internal.component.external.model.TestFixturesSupport.TEST_FIXTURES_CAPABILITY_APPENDIX;

public class DefaultJvmComponentDependencies implements JvmComponentDependencies {
    private final Configuration implementation;
    private final Configuration compileOnly;
    private final Configuration runtimeOnly;
    private final Configuration annotationProcessor;

    @Inject
    public DefaultJvmComponentDependencies(Configuration implementation, Configuration compileOnly, Configuration runtimeOnly, Configuration annotationProcessor) {
        this.implementation = implementation;
        this.compileOnly = compileOnly;
        this.runtimeOnly = runtimeOnly;
        this.annotationProcessor = annotationProcessor;
    }

    @Inject
    protected DependencyHandler getDependencyHandler() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected ObjectFactory getObjectFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void implementation(Object dependency) {
        implementation(dependency, null);
    }

    @Override
    public void implementation(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(implementation, dependency, configuration);
    }

    @Override
    public void runtimeOnly(Object dependency) {
        runtimeOnly(dependency, null);
    }

    @Override
    public void runtimeOnly(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(runtimeOnly, dependency, configuration);
    }

    @Override
    public void compileOnly(Object dependency) {
        compileOnly(dependency, null);
    }

    @Override
    public void compileOnly(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(compileOnly, dependency, configuration);
    }

    @Override
    public void annotationProcessor(Object dependency) {
        annotationProcessor(dependency, null);
    }

    @Override
    public void annotationProcessor(Object dependency, @Nullable Action<? super Dependency> configuration) {
        doAdd(annotationProcessor, dependency, configuration);
    }

    @Override
    public Dependency gradleApi() {
        return getDependencyHandler().create(DependencyFactory.ClassPathNotation.GRADLE_API);
    }

    @Override
    public Dependency gradleTestKit() {
        return getDependencyHandler().create(DependencyFactory.ClassPathNotation.GRADLE_TEST_KIT);
    }

    @Override
    public Dependency localGroovy() {
        return getDependencyHandler().create(DependencyFactory.ClassPathNotation.LOCAL_GROOVY);
    }
    
    public Dependency testFixtures(Project project) {
        final ProjectDependency projectDependency = (ProjectDependency) getDependencyHandler().create(project);
        return testFixtures(projectDependency);
    }

    @Override
    public Dependency testFixtures(ProjectDependency projectDependency) {
        projectDependency.capabilities(new ProjectTestFixtures(projectDependency.getDependencyProject()));
        return projectDependency;
    }

    @Override
    public Dependency testFixtures(ModuleDependency moduleDependency) {
        moduleDependency.capabilities(capabilities -> {
            capabilities.requireCapability(new ImmutableCapability(moduleDependency.getGroup(), moduleDependency.getName() + TEST_FIXTURES_CAPABILITY_APPENDIX, null));
        });
        return moduleDependency;
    }

    private void doAdd(Configuration bucket, Object dependency, @Nullable Action<? super Dependency> configuration) {
        if (dependency instanceof ProviderConvertible<?>) {
            doAdd(bucket, ((ProviderConvertible<?>) dependency).asProvider(), configuration);
        } else if (dependency instanceof ProviderInternal<?>) {
            ProviderInternal<?> provider = (ProviderInternal<?>) dependency;
            if (provider.getType()!=null && ExternalModuleDependencyBundle.class.isAssignableFrom(provider.getType())) {
                ExternalModuleDependencyBundle bundle = Cast.uncheckedCast(provider.get());
                for (MinimalExternalModuleDependency dep : bundle) {
                    doAddEager(bucket, dep, configuration);
                }
            } else {
                doAddLazy(bucket, (Provider<?>) dependency, configuration);
            }
        } else if (dependency instanceof Provider<?>) {
            doAddLazy(bucket, (Provider<?>) dependency, configuration);
        } else {
            doAddEager(bucket, dependency, configuration);
        }
    }

    private void doAddEager(Configuration bucket, Object dependency, @Nullable Action<? super Dependency> configuration) {
        Dependency created = create(dependency, configuration);
        bucket.getDependencies().add(created);
    }

    private void doAddLazy(Configuration bucket, Provider<?> dependencyProvider, @Nullable Action<? super Dependency> configuration) {
        Provider<Dependency> lazyDependency = dependencyProvider.map(mapDependencyProvider(bucket, configuration));
        bucket.getDependencies().addLater(lazyDependency);
    }

    private <T> Transformer<Dependency, T> mapDependencyProvider(Configuration bucket, @Nullable Action<? super Dependency> configuration) {
        return lazyNotation -> {
            if (lazyNotation instanceof Configuration) {
                throw new InvalidUserDataException("Adding a configuration as a dependency using a provider isn't supported. You should call " + bucket.getName() + ".extendsFrom(" + ((Configuration) lazyNotation).getName() + ") instead");
            }
            return create(lazyNotation, configuration);
        };
    }

    private Dependency create(Object dependency, @Nullable Action<? super Dependency> configuration) {
        final Dependency created = getDependencyHandler().create(dependency);
        if (configuration != null) {
            configuration.execute(created);
        }
        return created;
    }
}
