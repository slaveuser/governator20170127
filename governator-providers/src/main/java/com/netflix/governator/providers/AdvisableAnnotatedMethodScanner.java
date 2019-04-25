package com.netflix.governator.providers;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.BindingAnnotation;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.BindingTargetVisitor;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.HasDependencies;
import com.google.inject.spi.InjectionPoint;
import com.google.inject.spi.ModuleAnnotatedMethodScanner;
import com.google.inject.spi.ProviderInstanceBinding;
import com.google.inject.spi.ProviderWithExtensionVisitor;
import com.google.inject.spi.Toolable;
import com.google.inject.util.Types;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import javax.inject.Provider;

public final class AdvisableAnnotatedMethodScanner extends ModuleAnnotatedMethodScanner {
    @Retention(RUNTIME) 
    @BindingAnnotation
    @interface AdviceElement {
        enum Type {
            SOURCE, ADVICE
        }
        
        /**
         * Unique ID that so multiple @Advice with the same return type may be defined without
         * resulting in a duplicate binding exception.
         */
        int uniqueId();
        
        /**
         * Name derived from a toString() of a qualifier and is used to match @Advice annotated method
         * with their @AdviceProvision
         */
        String name();
        
        Type type();
    }
    
    private static final AdvisableAnnotatedMethodScanner INSTANCE = new AdvisableAnnotatedMethodScanner();
    
    public static AdvisableAnnotatedMethodScanner scanner() {
        return INSTANCE;
    }

    public static Module asModule() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                binder().scanModulesForAnnotatedMethods(AdvisableAnnotatedMethodScanner.INSTANCE);
            }
        };
    }
    
    private AdvisableAnnotatedMethodScanner() {
    }

    @Override
    public Set<? extends Class<? extends Annotation>> annotationClasses() {
        return new HashSet<>(Arrays.asList(ProvidesWithAdvice.class, Advises.class));
    }

    @Override
    public <T> Key<T> prepareMethod(Binder binder, Annotation annotation, Key<T> key, InjectionPoint injectionPoint) {
        String elementName = key.hasAttributes() ? key.getAnnotation().toString() : "";

        if (annotation instanceof ProvidesWithAdvice) {
            AdviceElement element = new AdviceElementImpl(elementName, AdviceElement.Type.SOURCE, annotation);
            Key<T> uniqueKey = Key.get(key.getTypeLiteral(), element);
            binder.bind(key).toProvider(new AdvisedProvider<T>(key.getTypeLiteral(), element.name(), annotation, binder.getProvider(uniqueKey)));
            return uniqueKey;
        } else if (annotation instanceof Advises) {
            Method method = (Method) injectionPoint.getMember();
            Preconditions.checkArgument(UnaryOperator.class.isAssignableFrom(method.getReturnType()), "Return type fo @Advice method must be UnaryOperator");
            return Key.get(key.getTypeLiteral(), new AdviceElementImpl(elementName, AdviceElement.Type.ADVICE, annotation));
        }
        return key;
    }
    
    private static class AdvisedProvider<T> implements ProviderWithExtensionVisitor<T>, HasDependencies  {
        private final Set<Dependency<?>> dependencies = new HashSet<>();
        private final String name;
        private final Provider<T> delegate;
        private final List<ProvisionAdviceHolder<UnaryOperator<T>>> adviceBindings = new ArrayList<>();
        private final TypeLiteral<UnaryOperator<T>> advisesType;
        
        public AdvisedProvider(TypeLiteral<T> typeLiteral, String name, Annotation annotation, Provider<T> delegate) {
            this.name = name;
            this.delegate = delegate;
            this.advisesType = (TypeLiteral<UnaryOperator<T>>) TypeLiteral.get(Types.newParameterizedType(UnaryOperator.class, typeLiteral.getType()));
        }
        
        @Override
        public T get() {
            return adviceBindings
                .stream()
                .map(advice -> advice.binding.getProvider().get())
                .reduce(delegate.get(), 
                        (advised, advice) -> advice.apply(advised),
                        (current, next) -> next);
        }

        @Override
        public Set<Dependency<?>> getDependencies() {
            return dependencies;
        }

        @Override
        public <B, V> V acceptExtensionVisitor(BindingTargetVisitor<B, V> visitor,
                ProviderInstanceBinding<? extends B> binding) {
            return visitor.visit(binding);
        }

        @SuppressWarnings("unchecked")
        @Toolable
        @javax.inject.Inject
        protected void initialize(Injector injector) {
            for (Binding<?> binding : injector.findBindingsByType(advisesType)) {
                Key<?> bindingKey = binding.getKey();
                if (bindingKey.hasAttributes() && AdviceElement.class.isAssignableFrom(bindingKey.getAnnotationType())) {
                    AdviceElementImpl adviceElement = (AdviceElementImpl) bindingKey.getAnnotation();
                    if (name.equals(adviceElement.name())) {
                        if (adviceElement.type() == AdviceElement.Type.ADVICE) {
                            adviceBindings.add(new ProvisionAdviceHolder<UnaryOperator<T>>((Binding<UnaryOperator<T>>) binding, adviceElement));
                        }
                        dependencies.add(Dependency.get(bindingKey));
                    }
                }
            }
            
            adviceBindings.sort(comparator);
        }
    }

    static Comparator<ProvisionAdviceHolder<?>> comparator = new Comparator<ProvisionAdviceHolder<?>>() {
        @Override
        public int compare(ProvisionAdviceHolder<?> o1, ProvisionAdviceHolder<?> o2) {
            int rv =  Integer.compare(o1.order, o2.order);
            if (rv == 0) {
                return Integer.compare(System.identityHashCode(o1.hashCode()), System.identityHashCode(o2.hashCode()));
            }
            return rv;
        }
    };
    
    static class ProvisionAdviceHolder<T>  {
        private Binding<T> binding;
        int order;
        
        public ProvisionAdviceHolder(Binding<T> binding, AdviceElementImpl element) {
            this.order = ((Advises)(element.getQualifier())).order();
            this.binding = binding;
        }
    }
    
    static class AdviceElementImpl implements AdviceElement {
        private static final AtomicInteger counter = new AtomicInteger();
        private final int id = counter.incrementAndGet();
        private final String name;
        private final Type type;
        private final Annotation qualifier;
        
        public AdviceElementImpl(String name, Type type, Annotation qualifier) {
            this.name = name;
            this.type = type;
            this.qualifier = qualifier;
        }
        
        @Override
        public Class<? extends Annotation> annotationType() {
            return AdviceElement.class;
        }
        
        public Annotation getQualifier() {
            return qualifier;
        }

        @Override
        public int uniqueId() {
            return id;
        }
        
        @Override
        public String name() {
            return name;
        }

        @Override
        public Type type() {
            return type;
        }
        
        @Override
        public boolean equals(Object o) {
            return o instanceof AdviceElement
                    && ((AdviceElement) o).name().equals(name())
                    && ((AdviceElement) o).uniqueId() == uniqueId()
                    && ((AdviceElement) o).type() == type();
        }
        
        @Override
        public int hashCode() {
            return ((127 * "name".hashCode()) ^ name().hashCode())
                 + ((127 * "id".hashCode()) ^ uniqueId())
                 + ((127 * "type".hashCode()) ^ type.hashCode());
        }
        public String toString() {
            return "@" + getClass().getSimpleName()
                + "(name=" + name() 
                + ", type=" + type() 
                + ", id=" + uniqueId() + ")";
        }
    }
}
