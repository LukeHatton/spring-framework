/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.aot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import javax.lang.model.element.Modifier;

import org.springframework.aot.generate.GeneratedClass;
import org.springframework.aot.generate.GeneratedMethod;
import org.springframework.aot.generate.GeneratedMethods;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.generate.MethodReference;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.MethodParameter;
import org.springframework.javapoet.ClassName;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Generates a method that returns a {@link BeanDefinition} to be registered.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @since 6.0
 * @see BeanDefinitionMethodGeneratorFactory
 */
class BeanDefinitionMethodGenerator {

	private final BeanDefinitionMethodGeneratorFactory methodGeneratorFactory;

	private final RegisteredBean registeredBean;

	private final Executable constructorOrFactoryMethod;

	@Nullable
	private final String innerBeanPropertyName;

	private final List<BeanRegistrationAotContribution> aotContributions;


	/**
	 * Create a new {@link BeanDefinitionMethodGenerator} instance.
	 * @param methodGeneratorFactory the method generator factory
	 * @param registeredBean the registered bean
	 * @param innerBeanPropertyName the inner bean property name
	 * @param aotContributions the AOT contributions
	 */
	BeanDefinitionMethodGenerator(
			BeanDefinitionMethodGeneratorFactory methodGeneratorFactory,
			RegisteredBean registeredBean, @Nullable String innerBeanPropertyName,
			List<BeanRegistrationAotContribution> aotContributions) {

		this.methodGeneratorFactory = methodGeneratorFactory;
		this.registeredBean = registeredBean;
		this.constructorOrFactoryMethod = registeredBean.resolveConstructorOrFactoryMethod();
		this.innerBeanPropertyName = innerBeanPropertyName;
		this.aotContributions = aotContributions;
	}


	/**
	 * Generate the method that returns the {@link BeanDefinition} to be
	 * registered.
	 * @param generationContext the generation context
	 * @param beanRegistrationsCode the bean registrations code
	 * @return a reference to the generated method.
	 */
	MethodReference generateBeanDefinitionMethod(GenerationContext generationContext,
			BeanRegistrationsCode beanRegistrationsCode) {

		registerRuntimeHintsIfNecessary(generationContext.getRuntimeHints());
		BeanRegistrationCodeFragments codeFragments = getCodeFragments(generationContext,
				beanRegistrationsCode);
		ClassName target = codeFragments.getTarget(this.registeredBean, this.constructorOrFactoryMethod);
		if (!target.canonicalName().startsWith("java.")) {
			GeneratedClass generatedClass = lookupGeneratedClass(generationContext, target);
			GeneratedMethods generatedMethods = generatedClass.getMethods().withPrefix(getName());
			GeneratedMethod generatedMethod = generateBeanDefinitionMethod(generationContext,
					generatedClass.getName(), generatedMethods, codeFragments, Modifier.PUBLIC);
			return generatedMethod.toMethodReference();
		}
		GeneratedMethods generatedMethods = beanRegistrationsCode.getMethods().withPrefix(getName());
		GeneratedMethod generatedMethod = generateBeanDefinitionMethod(generationContext,
				beanRegistrationsCode.getClassName(), generatedMethods, codeFragments, Modifier.PRIVATE);
		return generatedMethod.toMethodReference();
	}

	/**
	 * Return the {@link GeneratedClass} to use for the specified {@code target}.
	 * <p>If the target class is an inner class, a corresponding inner class in
	 * the original structure is created.
	 * @param generationContext the generation context to use
	 * @param target the chosen target class name for the bean definition
	 * @return the generated class to use
	 */
	private static GeneratedClass lookupGeneratedClass(GenerationContext generationContext, ClassName target) {
		ClassName topLevelClassName = target.topLevelClassName();
		GeneratedClass generatedClass = generationContext.getGeneratedClasses()
				.getOrAddForFeatureComponent("BeanDefinitions", topLevelClassName, type -> {
					type.addJavadoc("Bean definitions for {@link $T}", topLevelClassName);
					type.addModifiers(Modifier.PUBLIC);
				});
		List<String> names = target.simpleNames();
		if (names.size() == 1) {
			return generatedClass;
		}
		List<String> namesToProcess = names.subList(1, names.size());
		ClassName currentTargetClassName = topLevelClassName;
		GeneratedClass tmp = generatedClass;
		for (String nameToProcess : namesToProcess) {
			currentTargetClassName = currentTargetClassName.nestedClass(nameToProcess);
			tmp = createInnerClass(tmp, nameToProcess + "__BeanDefinitions", currentTargetClassName);
		}
		return tmp;
	}

	private static GeneratedClass createInnerClass(GeneratedClass generatedClass,
			String name, ClassName target) {
		return generatedClass.getOrAdd(name, type -> {
			type.addJavadoc("Bean definitions for {@link $T}", target);
			type.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
		});
	}

	private BeanRegistrationCodeFragments getCodeFragments(GenerationContext generationContext,
			BeanRegistrationsCode beanRegistrationsCode) {

		BeanRegistrationCodeFragments codeFragments = new DefaultBeanRegistrationCodeFragments(
				beanRegistrationsCode, this.registeredBean, this.methodGeneratorFactory);
		for (BeanRegistrationAotContribution aotContribution : this.aotContributions) {
			codeFragments = aotContribution.customizeBeanRegistrationCodeFragments(generationContext, codeFragments);
		}
		return codeFragments;
	}

	private GeneratedMethod generateBeanDefinitionMethod(
			GenerationContext generationContext, ClassName className,
			GeneratedMethods generatedMethods, BeanRegistrationCodeFragments codeFragments,
			Modifier modifier) {

		BeanRegistrationCodeGenerator codeGenerator = new BeanRegistrationCodeGenerator(
				className, generatedMethods, this.registeredBean,
				this.constructorOrFactoryMethod, codeFragments);
		this.aotContributions.forEach(aotContribution -> aotContribution
				.applyTo(generationContext, codeGenerator));
		return generatedMethods.add("getBeanDefinition", method -> {
			method.addJavadoc("Get the $L definition for '$L'",
					(!this.registeredBean.isInnerBean()) ? "bean" : "inner-bean",
					getName());
			method.addModifiers(modifier, Modifier.STATIC);
			method.returns(BeanDefinition.class);
			method.addCode(codeGenerator.generateCode(generationContext));
		});
	}

	private String getName() {
		if (this.innerBeanPropertyName != null) {
			return this.innerBeanPropertyName;
		}
		if (!this.registeredBean.isGeneratedBeanName()) {
			return getSimpleBeanName(this.registeredBean.getBeanName());
		}
		RegisteredBean nonGeneratedParent = this.registeredBean;
		while (nonGeneratedParent != null && nonGeneratedParent.isGeneratedBeanName()) {
			nonGeneratedParent = nonGeneratedParent.getParent();
		}
		if (nonGeneratedParent != null) {
			return getSimpleBeanName(nonGeneratedParent.getBeanName()) + "InnerBean";
		}
		return "innerBean";
	}

	private String getSimpleBeanName(String beanName) {
		int lastDot = beanName.lastIndexOf('.');
		beanName = (lastDot != -1) ? beanName.substring(lastDot + 1) : beanName;
		int lastDollar = beanName.lastIndexOf('$');
		beanName = (lastDollar != -1) ? beanName.substring(lastDollar + 1) : beanName;
		return StringUtils.uncapitalize(beanName);
	}

	private void registerRuntimeHintsIfNecessary(RuntimeHints runtimeHints) {
		if (this.registeredBean.getBeanFactory() instanceof DefaultListableBeanFactory dlbf) {
			ProxyRuntimeHintsRegistrar registrar = new ProxyRuntimeHintsRegistrar(dlbf.getAutowireCandidateResolver());
			if (this.constructorOrFactoryMethod instanceof Method method) {
				registrar.registerRuntimeHints(runtimeHints, method);
			}
			else if (this.constructorOrFactoryMethod instanceof Constructor<?> constructor) {
				registrar.registerRuntimeHints(runtimeHints, constructor);
			}
		}
	}


	private static class ProxyRuntimeHintsRegistrar {

		private final AutowireCandidateResolver candidateResolver;

		public ProxyRuntimeHintsRegistrar(AutowireCandidateResolver candidateResolver) {
			this.candidateResolver = candidateResolver;
		}

		public void registerRuntimeHints(RuntimeHints runtimeHints, Method method) {
			Class<?>[] parameterTypes = method.getParameterTypes();
			for (int i = 0; i < parameterTypes.length; i++) {
				MethodParameter methodParam = new MethodParameter(method, i);
				DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(methodParam, true);
				registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
			}
		}

		public void registerRuntimeHints(RuntimeHints runtimeHints, Constructor<?> constructor) {
			Class<?>[] parameterTypes = constructor.getParameterTypes();
			for (int i = 0; i < parameterTypes.length; i++) {
				MethodParameter methodParam = new MethodParameter(constructor, i);
				DependencyDescriptor dependencyDescriptor = new DependencyDescriptor(
						methodParam, true);
				registerProxyIfNecessary(runtimeHints, dependencyDescriptor);
			}
		}

		private void registerProxyIfNecessary(RuntimeHints runtimeHints, DependencyDescriptor dependencyDescriptor) {
			Class<?> proxyType = this.candidateResolver.getLazyResolutionProxyClass(dependencyDescriptor, null);
			if (proxyType != null && Proxy.isProxyClass(proxyType)) {
				runtimeHints.proxies().registerJdkProxy(proxyType.getInterfaces());
			}
		}
	}

}
