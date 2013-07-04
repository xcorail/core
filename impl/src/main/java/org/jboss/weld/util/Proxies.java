/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.util;

import static org.jboss.weld.logging.messages.UtilMessage.CANNOT_PROXY_NON_CLASS_TYPE;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_ARRAY_TYPE;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_FINAL_TYPE_OR_METHOD;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_NO_CONSTRUCTOR;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_PRIMITIVE;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_PRIVATE_CONSTRUCTOR;
import static org.jboss.weld.logging.messages.ValidatorMessage.NOT_PROXYABLE_UNKNOWN;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.enterprise.inject.spi.Bean;

import org.jboss.weld.bootstrap.api.ServiceRegistry;
import org.jboss.weld.exceptions.IllegalArgumentException;
import org.jboss.weld.exceptions.UnproxyableResolutionException;
import org.jboss.weld.security.GetDeclaredConstructorAction;
import org.jboss.weld.util.reflection.Reflections;
import org.jboss.weld.util.reflection.instantiation.InstantiatorFactory;

/**
 * Utilties for working with Javassist proxies
 *
 * @author Nicklas Karlsson
 * @author Pete Muir
 * @author Tomaz Cerar
 * @author Ales Justin
 */
@SuppressWarnings({"ThrowableResultOfMethodCallIgnored", "NullableProblems"})
public class Proxies {

    public static class TypeInfo {

        private final Set<Class<?>> interfaces;
        private final Set<Class<?>> classes;

        private TypeInfo() {
            super();
            this.interfaces = new LinkedHashSet<Class<?>>();
            this.classes = new LinkedHashSet<Class<?>>();
        }

        public Class<?> getSuperClass() {
            if (classes.isEmpty()) {
                return Object.class;
            }
            Iterator<Class<?>> it = classes.iterator();
            Class<?> superclass = it.next();
            while (it.hasNext()) {
                Class<?> clazz = it.next();
                if (superclass.isAssignableFrom(clazz)) {
                    superclass = clazz;
                }
            }
            return superclass;
        }

        public Class<?> getSuperInterface() {
            if (interfaces.isEmpty()) {
                return null;
            }
            Iterator<Class<?>> it = interfaces.iterator();
            Class<?> superclass = it.next();
            while (it.hasNext()) {
                Class<?> clazz = it.next();
                if (superclass.isAssignableFrom(clazz)) {
                    superclass = clazz;
                }
            }
            return superclass;
        }

        private TypeInfo add(Type type) {
            if (type instanceof Class<?>) {
                Class<?> clazz = (Class<?>) type;
                if (clazz.isInterface()) {
                    interfaces.add(clazz);
                } else {
                    classes.add(clazz);
                }
            } else if (type instanceof ParameterizedType) {
                add(((ParameterizedType) type).getRawType());
            } else {
                throw new IllegalArgumentException(CANNOT_PROXY_NON_CLASS_TYPE, type);
            }
            return this;
        }

        public Set<Class<?>> getClasses() {
            return Collections.unmodifiableSet(classes);
        }

        public Set<Class<?>> getInterfaces() {
            return Collections.unmodifiableSet(interfaces);
        }

        public static TypeInfo of(Set<? extends Type> types) {
            TypeInfo typeInfo = new TypeInfo();
            for (Type type : types) {
                typeInfo.add(type);
            }
            return typeInfo;
        }

    }

    private Proxies() {
    }

    /**
     * Indicates if a class is proxyable
     *
     * @param type The class to test
     * @return True if proxyable, false otherwise
     */
    public static boolean isTypeProxyable(Type type, ServiceRegistry services) {
        return getUnproxyableTypeException(type, services) == null;
    }

    public static UnproxyableResolutionException getUnproxyableTypeException(Type type, ServiceRegistry services) {
        return getUnproxyableTypeException(type, null, services);
    }

    /**
     * Indicates if a set of types are all proxyable
     *
     * @param declaringBean with types to test
     * @return True if proxyable, false otherwise
     */
    public static boolean isTypesProxyable(Bean<?> declaringBean, ServiceRegistry services) {
        return getUnproxyableTypesException(declaringBean, services) == null;
    }

    /**
     * Indicates if a set of types are all proxyable
     *
     * @param types The types to test
     * @return True if proxyable, false otherwise
     */
    public static boolean isTypesProxyable(Iterable<? extends Type> types, ServiceRegistry services) {
        return getUnproxyableTypesException(types, services) == null;
    }

    public static UnproxyableResolutionException getUnproxyableTypesException(Bean<?> declaringBean, ServiceRegistry services) {
        if (declaringBean == null) {
            throw new java.lang.IllegalArgumentException("Null declaring bean!");
        }

        return getUnproxyableTypesExceptionInt(declaringBean.getTypes(), declaringBean, services);
    }

    public static UnproxyableResolutionException getUnproxyableTypesException(Iterable<? extends Type> types, ServiceRegistry services) {
        return getUnproxyableTypesExceptionInt(types, null, services);
    }


    public static UnproxyableResolutionException getUnproxyableTypeException(Type type, Bean<?> declaringBean, ServiceRegistry services) {
        if (type instanceof Class<?>) {
            return getUnproxyableClassException((Class<?>) type, declaringBean, services);
        } else if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return getUnproxyableClassException((Class<?>) rawType, declaringBean, services);
            }
        }
        return new UnproxyableResolutionException(NOT_PROXYABLE_UNKNOWN, type, getDeclaringBeanInfo(declaringBean));
    }

    // --- private

    private static UnproxyableResolutionException getUnproxyableTypesExceptionInt(Iterable<? extends Type> types, Bean<?> declaringBean, ServiceRegistry services) {
        for (Type apiType : types) {
            if (Object.class.equals(apiType)) {
                continue;
            }
            UnproxyableResolutionException e = getUnproxyableTypeException(apiType, declaringBean, services);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static UnproxyableResolutionException getUnproxyableClassException(Class<?> clazz, Bean<?> declaringBean, ServiceRegistry services) {
        if (clazz.isInterface()) {
            return null;
        }

        Constructor<?> constructor;
        try {
            constructor = AccessController.doPrivileged(GetDeclaredConstructorAction.of(clazz));
        } catch (PrivilegedActionException e) {
            InstantiatorFactory factory = services.get(InstantiatorFactory.class);
            if (factory == null || !(factory.useInstantiators())) {
                return new UnproxyableResolutionException(NOT_PROXYABLE_NO_CONSTRUCTOR, clazz, getDeclaringBeanInfo(declaringBean));
            } else {
                return null;
            }
        }
        if (constructor == null) {
            return new UnproxyableResolutionException(NOT_PROXYABLE_NO_CONSTRUCTOR, clazz, getDeclaringBeanInfo(declaringBean));
        } else if (Modifier.isPrivate(constructor.getModifiers())) {
            InstantiatorFactory factory = services.get(InstantiatorFactory.class);
            if (factory == null || !(factory.useInstantiators())) {
                return new UnproxyableResolutionException(NOT_PROXYABLE_PRIVATE_CONSTRUCTOR, clazz, constructor, getDeclaringBeanInfo(declaringBean));
            } else {
                return null;
            }
        } else if (Reflections.isTypeOrAnyMethodFinal(clazz)) {
            return new UnproxyableResolutionException(NOT_PROXYABLE_FINAL_TYPE_OR_METHOD, clazz, Reflections.getNonPrivateFinalMethodOrType(clazz), getDeclaringBeanInfo(declaringBean));
        } else if (clazz.isPrimitive()) {
            return new UnproxyableResolutionException(NOT_PROXYABLE_PRIMITIVE, clazz, getDeclaringBeanInfo(declaringBean));
        } else if (Reflections.isArrayType(clazz)) {
            return new UnproxyableResolutionException(NOT_PROXYABLE_ARRAY_TYPE, clazz, getDeclaringBeanInfo(declaringBean));
        } else {
            return null;
        }
    }

    private static Object getDeclaringBeanInfo(Bean<?> bean) {
        return (bean != null) ? bean : "<unknown javax.enterprise.inject.spi.Bean instance>";
    }
}
