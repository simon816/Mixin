/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
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

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.gen.AccessorInfo;
import org.spongepowered.asm.mixin.gen.AccessorInfo.AccessorType;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;
import org.spongepowered.asm.mixin.refmap.IReferenceMapperContext;
import org.spongepowered.asm.mixin.refmap.ReferenceMapper;
import org.spongepowered.asm.obfuscation.mapping.common.MappingField;
import org.spongepowered.asm.obfuscation.mapping.common.MappingMethod;
import org.spongepowered.tools.MirrorUtils;
import org.spongepowered.tools.obfuscation.ReferenceManager.ReferenceConflictException;
import org.spongepowered.tools.obfuscation.interfaces.IMixinAnnotationProcessor;

import com.google.common.base.Strings;

/**
 * A module for {@link AnnotatedMixin} which handles accessors
 */
public class AnnotatedMixinElementHandlerAccessor extends AnnotatedMixinElementHandler implements IReferenceMapperContext {

    /**
     * Accessor element
     */
    static class AnnotatedElementAccessor extends AnnotatedElement<ExecutableElement> {
        
        private final TypeMirror returnType;
        
        private String targetName;
        
        public AnnotatedElementAccessor(ExecutableElement element, AnnotationMirror annotation) {
            super(element, annotation);
            this.returnType = this.getElement().getReturnType();
        }
        
        public String getAnnotationValue() {
            return MirrorUtils.getAnnotationValue(this.getAnnotation());
        }

        public TypeMirror getTargetType() {
            switch (this.getAccessorType()) {
                case FIELD_GETTER:
                    return this.returnType;
                case FIELD_SETTER:
                    return this.getElement().getParameters().get(0).asType();
                default:
                    return null;
            }
        }
        
        public String getTargetTypeName() {
            return MirrorUtils.getTypeName(this.getTargetType());
        }
        
        public String getAccessorDesc() {
            return MirrorUtils.getInternalName(this.getTargetType());
        }
        
        public MemberInfo getContext() {
            return new MemberInfo(this.getTargetName(), null, this.getAccessorDesc());
        }
        
        public AccessorType getAccessorType() {
            return this.returnType.getKind() == TypeKind.VOID ? AccessorType.FIELD_SETTER : AccessorType.FIELD_GETTER;
        }

        public void setTargetName(String targetName) {
            this.targetName = targetName;
        }
        
        public String getTargetName() {
            return this.targetName;
        }
        
        @Override
        public String toString() {
            return this.targetName != null ? this.targetName : "<invalid>";
        }
    }
    
    /**
     * Invoker element
     */
    static class AnnotatedElementInvoker extends AnnotatedElementAccessor {

        public AnnotatedElementInvoker(ExecutableElement element, AnnotationMirror annotation) {
            super(element, annotation);
        }
        
        @Override
        public String getAccessorDesc() {
            return MirrorUtils.generateSignature(this.getElement());
        }
        
        @Override
        public AccessorType getAccessorType() {
            return AccessorType.METHOD_PROXY;
        }
        
        @Override
        public String getTargetTypeName() {
            return MirrorUtils.getJavaSignature(this.getElement());
        }
        
    }
    
    public AnnotatedMixinElementHandlerAccessor(IMixinAnnotationProcessor ap, AnnotatedMixin mixin) {
        super(ap, mixin);
    }
    
    @Override
    public ReferenceMapper getReferenceMapper() {
        return null;
    }
    
    @Override
    public String getClassRef() {
        return this.mixin.getClassRef();
    }
    
    @Override
    public IMixinInfo getMixin() {
        throw new UnsupportedOperationException("MixinInfo not available at compile time");
    }

    public void registerAccessor(AnnotatedElementAccessor elem) {
        if (elem.getAccessorType() == null) {
            this.ap.printMessage(Kind.WARNING, "Unsupported accessor type", elem.getElement(), elem.getAnnotation());
            return;
        }

        String targetName = this.getAccessorTargetName(elem);
        if (targetName == null) {
            this.ap.printMessage(Kind.WARNING, "Cannot inflect accessor target name", elem.getElement(), elem.getAnnotation());
            return;
        }
        elem.setTargetName(targetName);
        
        for (TypeHandle target : this.mixin.getTargets()) {
            if (elem.getAccessorType() == AccessorType.METHOD_PROXY) {
                this.registerInvokerForTarget((AnnotatedElementInvoker)elem, target);
            } else {
                this.registerAccessorForTarget(elem, target);
            }
        }
    }

    private void registerAccessorForTarget(AnnotatedElementAccessor elem, TypeHandle target) {
        FieldHandle targetField = target.findField(elem.getTargetName(), elem.getTargetTypeName(), false);
        if (targetField == null) {
            this.ap.printMessage(Kind.ERROR, "Could not locate @Accessor target " + elem + " in target " + target, elem.getElement(),
                    elem.getAnnotation());
            return;
        }

        ObfuscationData<MappingField> obfData = this.obf.getDataProvider().getObfField(targetField.asMapping().move(target.getName()));
        if (obfData.isEmpty()) {
            String info = this.mixin.isMultiTarget() ? " in target " + target : "";
            this.ap.printMessage(Kind.WARNING, "Unable to locate obfuscation mapping" + info + " for @Accessor target " + elem,
                    elem.getElement(), elem.getAnnotation());
            return;
        }
        
        obfData = AnnotatedMixinElementHandler.<MappingField>stripOwnerData(obfData);

        try {
            this.obf.getReferenceManager().addFieldMapping(this.mixin.getClassRef(), elem.getTargetName(), elem.getContext(), obfData);
        } catch (ReferenceConflictException ex) {
            this.ap.printMessage(Kind.ERROR, "Mapping conflict for @Accessor target " + elem + ": " + ex.getNew() + " for target "
                    + target + " conflicts with existing mapping " + ex.getOld(), elem.getElement(), elem.getAnnotation());
        }
    }

    private void registerInvokerForTarget(AnnotatedElementInvoker elem, TypeHandle target) {
        MethodHandle targetMethod = target.findMethod(elem.getTargetName(), elem.getTargetTypeName(), false);
        if (targetMethod == null) {
            this.ap.printMessage(Kind.ERROR, "Could not locate @Invoker target " + elem + " in target " + target, elem.getElement(),
                    elem.getAnnotation());
            return;
        }
        
        ObfuscationData<MappingMethod> obfData = this.obf.getDataProvider().getObfMethod(targetMethod.asMapping().move(target.getName()));
        if (obfData.isEmpty()) {
            String info = this.mixin.isMultiTarget() ? " in target " + target : "";
            this.ap.printMessage(Kind.WARNING, "Unable to locate obfuscation mapping" + info + " for @Accessor target " + elem,
                    elem.getElement(), elem.getAnnotation());
            return;
        }
        
        obfData = AnnotatedMixinElementHandler.<MappingMethod>stripOwnerData(obfData);
        
        try {
            this.obf.getReferenceManager().addMethodMapping(this.mixin.getClassRef(), elem.getTargetName(), elem.getContext(), obfData);
        } catch (ReferenceConflictException ex) {
            this.ap.printMessage(Kind.ERROR, "Mapping conflict for @Invoker target " + elem + ": " + ex.getNew() + " for target "
                    + target + " conflicts with existing mapping " + ex.getOld(), elem.getElement(), elem.getAnnotation());
        }
    }
    
    private String getAccessorTargetName(AnnotatedElementAccessor elem) {
        String value = elem.getAnnotationValue();
        if (Strings.isNullOrEmpty(value)) {
            return this.inflectAccessorTarget(elem);
        }
        return value;
    }

    private String inflectAccessorTarget(AnnotatedElementAccessor elem) {
        return AccessorInfo.inflectTarget(elem.getSimpleName(), elem.getAccessorType(), "", this, false);
    }

}
