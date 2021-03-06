/*
 * Copyright (C) 2014 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dagger.internal.codegen;

import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Equivalence;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import dagger.Component;
import dagger.Provides;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodType;
import dagger.producers.Produces;
import dagger.producers.ProductionComponent;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import static com.google.auto.common.MoreElements.getAnnotationMirror;
import static com.google.auto.common.MoreElements.isAnnotationPresent;
import static com.google.common.base.Preconditions.checkState;
import static dagger.internal.codegen.ComponentDescriptor.isComponentContributionMethod;
import static dagger.internal.codegen.ComponentDescriptor.isComponentProductionMethod;
import static dagger.internal.codegen.ComponentDescriptor.Kind.PRODUCTION_COMPONENT;
import static dagger.internal.codegen.ConfigurationAnnotations.getComponentDependencies;
import static dagger.internal.codegen.ConfigurationAnnotations.getComponentModules;
import static dagger.internal.codegen.ConfigurationAnnotations.getTransitiveModules;
import static dagger.internal.codegen.MembersInjectionBinding.Strategy.DELEGATE;
import static dagger.internal.codegen.MembersInjectionBinding.Strategy.NO_OP;
import static javax.lang.model.util.ElementFilter.methodsIn;

/**
 * The canonical representation of a full-resolved graph.
 *
 * @author Gregory Kick
 */
@AutoValue
abstract class BindingGraph {
  abstract ComponentDescriptor componentDescriptor();
  abstract ImmutableSet<DependencyRequest> entryPoints();
  abstract ImmutableMap<TypeElement, ImmutableSet<TypeElement>> transitiveModules();
  abstract ImmutableMap<BindingKey, ResolvedBindings> resolvedBindings();
  abstract ImmutableMap<ExecutableElement, BindingGraph> subgraphs();

  @AutoValue
  abstract static class ResolvedBindings {
    abstract BindingKey bindingKey();
    abstract ImmutableSet<? extends Binding> ownedBindings();
    abstract ImmutableSet<? extends Binding> inheritedBindings();

    ImmutableSet<? extends Binding> bindings() {
      return new ImmutableSet.Builder<Binding>()
          .addAll(inheritedBindings())
          .addAll(ownedBindings())
          .build();
    }

    static ResolvedBindings create(
        BindingKey bindingKey,
        Set<? extends Binding> ownedBindings,
        Set<? extends Binding> inheritedBindings) {
      return new AutoValue_BindingGraph_ResolvedBindings(
          bindingKey, ImmutableSet.copyOf(ownedBindings), ImmutableSet.copyOf(inheritedBindings));
    }

    static ResolvedBindings create(
        BindingKey bindingKey,
        Binding... ownedBindings) {
      return new AutoValue_BindingGraph_ResolvedBindings(
          bindingKey, ImmutableSet.copyOf(ownedBindings), ImmutableSet.<Binding>of());
    }

    @SuppressWarnings("unchecked")  // checked by validator
    ImmutableSet<? extends ContributionBinding> ownedContributionBindings() {
      checkState(bindingKey().kind().equals(BindingKey.Kind.CONTRIBUTION));
      return (ImmutableSet<? extends ContributionBinding>) ownedBindings();
    }

    @SuppressWarnings("unchecked")  // checked by validator
    ImmutableSet<? extends ContributionBinding> contributionBindings() {
      checkState(bindingKey().kind().equals(BindingKey.Kind.CONTRIBUTION));
      return (ImmutableSet<? extends ContributionBinding>) bindings();
    }

    @SuppressWarnings("unchecked")  // checked by validator
    ImmutableSet<? extends MembersInjectionBinding> membersInjectionBindings() {
      checkState(bindingKey().kind().equals(BindingKey.Kind.MEMBERS_INJECTION));
      return (ImmutableSet<? extends MembersInjectionBinding>) bindings();
    }
  }

  static final class Factory {
    private final Elements elements;
    private final Types types;
    private final InjectBindingRegistry injectBindingRegistry;
    private final Key.Factory keyFactory;
    private final DependencyRequest.Factory dependencyRequestFactory;
    private final ProvisionBinding.Factory provisionBindingFactory;
    private final ProductionBinding.Factory productionBindingFactory;

    Factory(Elements elements,
        Types types,
        InjectBindingRegistry injectBindingRegistry,
        Key.Factory keyFactory,
        DependencyRequest.Factory dependencyRequestFactory,
        ProvisionBinding.Factory provisionBindingFactory,
        ProductionBinding.Factory productionBindingFactory) {
      this.elements = elements;
      this.types = types;
      this.injectBindingRegistry = injectBindingRegistry;
      this.keyFactory = keyFactory;
      this.dependencyRequestFactory = dependencyRequestFactory;
      this.provisionBindingFactory = provisionBindingFactory;
      this.productionBindingFactory = productionBindingFactory;
    }

    BindingGraph create(ComponentDescriptor componentDescriptor) {
      return create(Optional.<RequestResolver>absent(), componentDescriptor);
    }

    private BindingGraph create(Optional<RequestResolver> parentResolver,
        ComponentDescriptor componentDescriptor) {
      ImmutableSet.Builder<ProvisionBinding> explicitProvisionBindingsBuilder =
          ImmutableSet.builder();
      ImmutableSet.Builder<ProductionBinding> explicitProductionBindingsBuilder =
          ImmutableSet.builder();
      AnnotationMirror componentAnnotation = componentDescriptor.componentAnnotation();

      // binding for the component itself
      TypeElement componentDefinitionType = componentDescriptor.componentDefinitionType();
      ProvisionBinding componentBinding =
          provisionBindingFactory.forComponent(componentDefinitionType);
      explicitProvisionBindingsBuilder.add(componentBinding);

      // Collect Component dependencies.
      Optional<AnnotationMirror> componentMirror =
          getAnnotationMirror(componentDefinitionType, Component.class)
              .or(getAnnotationMirror(componentDefinitionType, ProductionComponent.class));
      ImmutableSet<TypeElement> componentDependencyTypes = componentMirror.isPresent()
          ? MoreTypes.asTypeElements(getComponentDependencies(componentMirror.get()))
          : ImmutableSet.<TypeElement>of();
      for (TypeElement componentDependency : componentDependencyTypes) {
        explicitProvisionBindingsBuilder.add(
            provisionBindingFactory.forComponent(componentDependency));
        List<ExecutableElement> dependencyMethods =
            ElementFilter.methodsIn(elements.getAllMembers(componentDependency));
        for (ExecutableElement method : dependencyMethods) {
          // MembersInjection methods aren't "provided" explicitly, so ignore them.
          if (isComponentContributionMethod(elements, method)) {
            if (componentDescriptor.kind().equals(PRODUCTION_COMPONENT)
                && isComponentProductionMethod(elements, method)) {
              explicitProductionBindingsBuilder.add(
                  productionBindingFactory.forComponentMethod(method));
            } else {
              explicitProvisionBindingsBuilder.add(
                  provisionBindingFactory.forComponentMethod(method));
            }
          }
        }
      }

      // Collect transitive modules provisions.
      ImmutableSet<TypeElement> moduleTypes =
          MoreTypes.asTypeElements(getComponentModules(componentAnnotation));

      ImmutableMap<TypeElement, ImmutableSet<TypeElement>> transitiveModules =
          getTransitiveModules(types, elements, moduleTypes);
      for (TypeElement module : transitiveModules.keySet()) {
        // traverse the modules, collect the bindings
        List<ExecutableElement> moduleMethods = methodsIn(elements.getAllMembers(module));
        for (ExecutableElement moduleMethod : moduleMethods) {
          if (isAnnotationPresent(moduleMethod, Provides.class)) {
            explicitProvisionBindingsBuilder.add(
                provisionBindingFactory.forProvidesMethod(moduleMethod, module.asType()));
          }
          if (isAnnotationPresent(moduleMethod, Produces.class)) {
            explicitProductionBindingsBuilder.add(
                productionBindingFactory.forProducesMethod(moduleMethod, module.asType()));
           }
        }
      }

      RequestResolver requestResolver = new RequestResolver(
          parentResolver,
          componentDescriptor.wrappedScope(),
          explicitBindingsByKey(explicitProvisionBindingsBuilder.build()),
          explicitBindingsByKey(explicitProductionBindingsBuilder.build()));
      ImmutableSetMultimap<ComponentMethodType, ExecutableElement> componentMethods =
          componentDescriptor.componentMethods();

      ImmutableSet<DependencyRequest> componentMethodRequests =
          componentMethodRequests(componentMethods);
      for (DependencyRequest componentMethodRequest : componentMethodRequests) {
        requestResolver.resolve(componentMethodRequest);
      }

      ImmutableMap.Builder<ExecutableElement, BindingGraph> subgraphsBuilder =
          ImmutableMap.builder();
      for (Entry<ExecutableElement, ComponentDescriptor> subcomponentEntry :
          componentDescriptor.subcomponents().entrySet()) {
        subgraphsBuilder.put(subcomponentEntry.getKey(),
            create(Optional.of(requestResolver), subcomponentEntry.getValue()));
      }

      return new AutoValue_BindingGraph(
          componentDescriptor,
          componentMethodRequests,
          transitiveModules,
          ImmutableMap.copyOf(requestResolver.resolvedBindings),
          subgraphsBuilder.build());
    }

    private <B extends ContributionBinding> ImmutableSetMultimap<Key, B> explicitBindingsByKey(
        Iterable<? extends B> bindings) {
      // Multimaps.index() doesn't do ImmutableSetMultimaps.
      ImmutableSetMultimap.Builder<Key, B> builder = ImmutableSetMultimap.builder();
      for (B binding : bindings) {
        builder.put(binding.key(), binding);
      }
      return builder.build();
    }

    private ImmutableSet<DependencyRequest> componentMethodRequests(
        ImmutableSetMultimap<ComponentMethodType, ExecutableElement> componentMethods) {
      ImmutableSet.Builder<DependencyRequest> interfaceRequestsBuilder = ImmutableSet.builder();
      for (ExecutableElement provisionMethod : componentMethods.get(ComponentMethodType.PROVISON)) {
        interfaceRequestsBuilder.add(
            dependencyRequestFactory.forComponentProvisionMethod(provisionMethod));
      }
      for (ExecutableElement productionMethod :
          componentMethods.get(ComponentMethodType.PRODUCTION)) {
        interfaceRequestsBuilder.add(
            dependencyRequestFactory.forComponentProductionMethod(productionMethod));
      }
      for (ExecutableElement membersInjectionMethod :
          componentMethods.get(ComponentMethodType.MEMBERS_INJECTION)) {
        interfaceRequestsBuilder.add(
            dependencyRequestFactory.forComponentMembersInjectionMethod(membersInjectionMethod));
      }
      return interfaceRequestsBuilder.build();
    }

    private final class RequestResolver {
      final Optional<RequestResolver> parentResolver;
      final Optional<Equivalence.Wrapper<AnnotationMirror>> targetScope;
      final ImmutableSetMultimap<Key, ProvisionBinding> explicitProvisionBindings;
      final ImmutableSetMultimap<Key, ProductionBinding> explicitProductionBindings;
      final Map<BindingKey, ResolvedBindings> resolvedBindings;
      final Deque<BindingKey> cycleStack = Queues.newArrayDeque();

      RequestResolver(Optional<RequestResolver> parentResolver,
          Optional<Equivalence.Wrapper<AnnotationMirror>> targetScope,
          ImmutableSetMultimap<Key, ProvisionBinding> explicitProvisionBindings,
          ImmutableSetMultimap<Key, ProductionBinding> explicitProductionBindings) {
        assert parentResolver != null;
        this.parentResolver = parentResolver;
        assert targetScope != null;
        this.targetScope = targetScope;
        assert explicitProvisionBindings != null;
        this.explicitProvisionBindings = explicitProvisionBindings;
        assert explicitProductionBindings != null;
        this.explicitProductionBindings = explicitProductionBindings;
        this.resolvedBindings = Maps.newLinkedHashMap();
      }

      /**
       *  Looks up the bindings associated with a given dependency request and returns them.  In the
       *  event that the binding is owned by a parent component it will trigger resolution in that
       *  component's resolver but will return an {@link Optional#absent} value.
       */
      ResolvedBindings lookUpBindings(DependencyRequest request) {
        BindingKey bindingKey = BindingKey.forDependencyRequest(request);
        switch (bindingKey.kind()) {
          case CONTRIBUTION:
            // First, check for explicit keys (those from modules and components)
            ImmutableSet<ProvisionBinding> explicitProvisionBindingsForKey =
                getExplicitProvisionBindings(bindingKey.key());
            ImmutableSet<ProductionBinding> explicitProductionBindingsForKey =
                getExplicitProductionBindings(bindingKey.key());

            // If the key is Map<K, V>, get its implicit binding keys, which are either
            // Map<K, Provider<V>> or Map<K, Producer<V>>, and grab their explicit bindings.
            Optional<Key> mapProviderKey = keyFactory.implicitMapProviderKeyFrom(bindingKey.key());
            ImmutableSet<ProvisionBinding> explicitMapProvisionBindings = ImmutableSet.of();
            if (mapProviderKey.isPresent()) {
              explicitMapProvisionBindings = getExplicitProvisionBindings(mapProviderKey.get());
            }

            Optional<Key> mapProducerKey = keyFactory.implicitMapProducerKeyFrom(bindingKey.key());
            ImmutableSet<ProductionBinding> explicitMapProductionBindings = ImmutableSet.of();
            if (mapProducerKey.isPresent()) {
              explicitMapProductionBindings = getExplicitProductionBindings(mapProducerKey.get());
            }

            if (!explicitProvisionBindingsForKey.isEmpty()
                || !explicitProductionBindingsForKey.isEmpty()) {
              // we have some explicit binding for this key, so we collect all explicit implicit map
              // bindings that might conflict with this and let the validator sort it out
              ImmutableSet.Builder<ContributionBinding> ownedBindings = ImmutableSet.builder();
              ImmutableSet.Builder<ContributionBinding> inheritedBindings = ImmutableSet.builder();
              for (ProvisionBinding provisionBinding :
                  Sets.union(explicitProvisionBindingsForKey, explicitMapProvisionBindings)) {
                Optional<RequestResolver> owningResolver = getOwningResolver(provisionBinding);
                if (owningResolver.isPresent() && !owningResolver.get().equals(this)) {
                  owningResolver.get().resolve(request);
                  inheritedBindings.add(provisionBinding);
                } else {
                  ownedBindings.add(provisionBinding);
                }
              }
              return ResolvedBindings.create(bindingKey,
                  ownedBindings
                      .addAll(explicitProductionBindingsForKey)
                      .addAll(explicitMapProductionBindings)
                      .build(),
                  inheritedBindings.build());
            } else {
              if (!explicitMapProductionBindings.isEmpty()) {
                // if we have any explicit Map<K, Producer<V>> bindings, then this Map<K, V> binding
                // must be considered an implicit ProductionBinding
                DependencyRequest implicitRequest =
                    dependencyRequestFactory.forImplicitMapBinding(request, mapProducerKey.get());
                return ResolvedBindings.create(bindingKey,
                    productionBindingFactory.forImplicitMapBinding(request, implicitRequest));
              } else if (!explicitMapProvisionBindings.isEmpty()) {
                // if there are Map<K, Provider<V>> bindings, then it'll be an implicit
                // ProvisionBinding
                DependencyRequest implicitRequest =
                    dependencyRequestFactory.forImplicitMapBinding(request, mapProviderKey.get());
                return ResolvedBindings.create(bindingKey,
                    provisionBindingFactory.forImplicitMapBinding(request, implicitRequest));
              } else {
                // no explicit binding, look it up.
                Optional<ProvisionBinding> provisionBinding =
                    injectBindingRegistry.getOrFindProvisionBinding(bindingKey.key());
                if (provisionBinding.isPresent()) {
                  Optional<RequestResolver> owningResolver =
                      getOwningResolver(provisionBinding.get());
                  if (owningResolver.isPresent() && !owningResolver.get().equals(this)) {
                    owningResolver.get().resolve(request);
                    return ResolvedBindings.create(
                        bindingKey, ImmutableSet.<Binding>of(),
                        provisionBinding.asSet());
                  }
                }
                return ResolvedBindings.create(
                    bindingKey, provisionBinding.asSet(), ImmutableSet.<Binding>of());
              }
            }
          case MEMBERS_INJECTION:
            // no explicit deps for members injection, so just look it up
            return ResolvedBindings.create(
                bindingKey, rollUpMembersInjectionBindings(bindingKey.key()));
          default:
            throw new AssertionError();
        }
      }

      private MembersInjectionBinding rollUpMembersInjectionBindings(Key key) {
        MembersInjectionBinding membersInjectionBinding =
            injectBindingRegistry.getOrFindMembersInjectionBinding(key);

        if (membersInjectionBinding.injectionStrategy().equals(DELEGATE)) {
          MembersInjectionBinding parentBinding = rollUpMembersInjectionBindings(
              membersInjectionBinding.parentInjectorRequest().get().key());
          if (parentBinding.injectionStrategy().equals(NO_OP)) {
            return membersInjectionBinding.withoutParentInjectorRequest();
          }
        }

        return membersInjectionBinding;
      }

      private Optional<RequestResolver> getOwningResolver(ProvisionBinding provisionBinding) {
        Optional<Equivalence.Wrapper<AnnotationMirror>> bindingScope =
            provisionBinding.wrappedScope();
        for (RequestResolver requestResolver : getResolverLineage()) {
          if (bindingScope.equals(requestResolver.targetScope)) {
            return Optional.of(requestResolver);
          }
        }
        return Optional.absent();
      }

      private ImmutableList<RequestResolver> getResolverLineage() {
        List<RequestResolver> resolverList = Lists.newArrayList();
        for (Optional<RequestResolver> currentResolver = Optional.of(this);
            currentResolver.isPresent();
            currentResolver = currentResolver.get().parentResolver) {
          resolverList.add(currentResolver.get());
        }
        return ImmutableList.copyOf(Lists.reverse(resolverList));
      }

      private ImmutableSet<ProvisionBinding> getExplicitProvisionBindings(Key requestKey) {
        ImmutableSet.Builder<ProvisionBinding> explicitBindingsForKey = ImmutableSet.builder();
        for (RequestResolver resolver : getResolverLineage()) {
          explicitBindingsForKey.addAll(
              resolver.explicitProvisionBindings.get(requestKey));
        }
        return explicitBindingsForKey.build();
      }

      private ImmutableSet<ProductionBinding> getExplicitProductionBindings(Key requestKey) {
        ImmutableSet.Builder<ProductionBinding> explicitBindingsForKey = ImmutableSet.builder();
        for (RequestResolver resolver : getResolverLineage()) {
          explicitBindingsForKey.addAll(
              resolver.explicitProductionBindings.get(requestKey));
        }
        return explicitBindingsForKey.build();
      }

      private Optional<ResolvedBindings> getPreviouslyResolvedBindings(
          final BindingKey bindingKey) {
        Optional<ResolvedBindings> result = Optional.fromNullable(resolvedBindings.get(bindingKey));
        if (result.isPresent()) {
          return result;
        } else if (parentResolver.isPresent()) {
          return parentResolver.get().getPreviouslyResolvedBindings(bindingKey);
        } else {
          return Optional.absent();
        }
      }

      void resolve(DependencyRequest request) {
        BindingKey bindingKey = BindingKey.forDependencyRequest(request);

        Optional<ResolvedBindings> previouslyResolvedBinding =
            getPreviouslyResolvedBindings(bindingKey);
        if (previouslyResolvedBinding.isPresent()
            && !(bindingKey.kind().equals(BindingKey.Kind.CONTRIBUTION)
                && !previouslyResolvedBinding.get().contributionBindings().isEmpty()
                && ContributionBinding.bindingTypeFor(
                    previouslyResolvedBinding.get().contributionBindings()).isMultibinding())) {
          return;
        }

        if (cycleStack.contains(bindingKey)) {
          // We found a cycle. Don't add a resolved binding, since the original request will add it
          // with all of the other resolved deps
          return;
        }

        cycleStack.push(bindingKey);
        try {
          ResolvedBindings bindings = lookUpBindings(request);
          for (Binding binding : bindings.ownedBindings()) {
            for (DependencyRequest dependency : binding.implicitDependencies()) {
              resolve(dependency);
            }
          }
          resolvedBindings.put(bindingKey, bindings);
        } finally {
          cycleStack.pop();
        }
      }
    }
  }
}
