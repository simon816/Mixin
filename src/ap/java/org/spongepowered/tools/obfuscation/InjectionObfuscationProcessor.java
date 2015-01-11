/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered.org <http://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.tools.obfuscation;

import java.lang.annotation.Annotation;
import java.util.Set;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.tools.MirrorUtils;

/**
 * Annotation processor which finds {@link Inject} and {@link At} annotations in mixin classes and generates SRG mappings
 */
@SupportedSourceVersion(SourceVersion.RELEASE_6)
@SupportedAnnotationTypes({
    "org.spongepowered.asm.mixin.injection.Inject",
    "org.spongepowered.asm.mixin.injection.ModifyArg",
    "org.spongepowered.asm.mixin.injection.Redirect",
    "org.spongepowered.asm.mixin.injection.At"
})
@SupportedOptions({ "reobfSrgFile", "outSrgFile" })
public class InjectionObfuscationProcessor extends MixinProcessor {
    
    /* (non-Javadoc)
     * @see javax.annotation.processing.AbstractProcessor#process(java.util.Set, javax.annotation.processing.RoundEnvironment)
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        
        this.processMixins(roundEnv);
        this.processInjectors(roundEnv, Inject.class);
        this.processInjectors(roundEnv, ModifyArg.class);
        this.processInjectors(roundEnv, Redirect.class);
        
        try {
            this.mixins.writeRefs();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        
        return true;
    }

    /**
     * Searches for {@link Inject} annotations and registers them with their parent mixins
     */
    private void processInjectors(RoundEnvironment roundEnv, Class<? extends Annotation> injectorClass) {
        for (Element elem : roundEnv.getElementsAnnotatedWith(injectorClass)) {
            Element parent = elem.getEnclosingElement();
            if (!(parent instanceof TypeElement)) {
                throw new IllegalStateException("@" + injectorClass.getSimpleName() + " element has unexpected parent with type "
                        + MirrorUtils.getElementType(parent));
            }
            
            AnnotationMirror inject = MirrorUtils.getAnnotation(elem, injectorClass);
            
            if (elem.getKind() == ElementKind.METHOD) {
                this.mixins.registerInjector((TypeElement)parent, (ExecutableElement)elem, inject);
            } else {
                this.processingEnv.getMessager().printMessage(Kind.WARNING,
                        "Found an @" + injectorClass.getSimpleName() + " annotation on an element which is not a method: " + elem.toString());
            }
        }
    }
}
