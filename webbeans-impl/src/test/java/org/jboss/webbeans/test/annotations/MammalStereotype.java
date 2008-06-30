package org.jboss.webbeans.test.annotations;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.webbeans.Stereotype;

import org.jboss.webbeans.test.components.Animal;

@Stereotype(requiredTypes=Animal.class)
@Target( { TYPE })
@Retention(RUNTIME)
@AnotherDeploymentType
public @interface MammalStereotype
{

}
