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
package org.spongepowered.asm.mixin.transformer;

import java.lang.annotation.Annotation;
import java.util.Iterator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.FieldNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.throwables.InvalidAccessorException;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Field;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;
import org.spongepowered.asm.mixin.transformer.MixinInfo.MixinClassNode;
import org.spongepowered.asm.mixin.transformer.MixinInfo.MixinMethodNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinRenamed;
import org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException;
import org.spongepowered.asm.util.ASMHelper;
import org.spongepowered.asm.util.Constants;

/**
 * <p>Mixin bytecode pre-processor. This class is responsible for bytecode pre-
 * processing tasks required to be performed on mixin bytecode before the mixin
 * can be applied. In previous versions the duties performed by this class were
 * performed by {@link MixinInfo}.</p>
 * 
 * <p>Before a mixin can be applied to the target class, it is necessary to
 * convert certain aspects of the mixin bytecode into the intended final form of
 * the mixin, this involves for example stripping the prefix from shadow and
 * soft-implemented methods. This preparation is done in two stages: first the
 * target-context-insensitive transformations are applied (this also acts as a
 * validation pass when the mixin is first loaded) and then transformations
 * which depend on the target class are applied in a second stage.</p>
 * 
 * <p>The validation pass propagates method renames into the metadata tree and
 * thus changes made during this phase are visible to all other mixins. The
 * target-context-sensitive pass on the other hand can only operate on private
 * class members for obvious reasons.</p>  
 */
class MixinPreProcessorStandard {
    
    /**
     * Logger
     */
    private static final Logger logger = LogManager.getLogger("mixin");

    /**
     * The mixin
     */
    protected final MixinInfo mixin;
    
    /**
     * Mixin class node
     */
    protected final MixinClassNode classNode;
    
    private final boolean verboseLogging, strictUnique;
    
    private boolean prepared, attached;

    MixinPreProcessorStandard(MixinInfo mixin, MixinClassNode classNode) {
        this.mixin = mixin;
        this.classNode = classNode;
        MixinEnvironment env = mixin.getParent().getEnvironment();
        this.verboseLogging = env.getOption(Option.DEBUG_VERBOSE);
        this.strictUnique = env.getOption(Option.DEBUG_UNIQUE);
    }

    /**
     * Run the first pass. Propagates changes into the metadata tree.
     * 
     * @return Prepared classnode
     */
    MixinPreProcessorStandard prepare() {
        if (this.prepared) {
            return this;
        }
        
        this.prepared = true;
        
        for (MixinMethodNode mixinMethod : this.classNode.mixinMethods) {
            Method method = this.mixin.getClassInfo().findMethod(mixinMethod);
            this.prepareMethod(mixinMethod, method);
        }
        
        for (FieldNode mixinField : this.classNode.fields) {
            this.prepareField(mixinField);
        }
        
        return this;
    }

    protected void prepareMethod(MixinMethodNode mixinMethod, Method method) {
        this.prepareShadow(mixinMethod, method);
        this.prepareSoftImplements(mixinMethod, method);
    }

    protected void prepareShadow(MixinMethodNode mixinMethod, Method method) {
        AnnotationNode shadowAnnotation = ASMHelper.getVisibleAnnotation(mixinMethod, Shadow.class);
        if (shadowAnnotation == null) {
            return;
        }
        
        String prefix = ASMHelper.<String>getAnnotationValue(shadowAnnotation, "prefix", Shadow.class);
        if (mixinMethod.name.startsWith(prefix)) {
            ASMHelper.setVisibleAnnotation(mixinMethod, MixinRenamed.class, "originalName", mixinMethod.name);
            String newName = mixinMethod.name.substring(prefix.length());
            mixinMethod.name = method.renameTo(newName);
        }
    }

    protected void prepareSoftImplements(MixinMethodNode mixinMethod, Method method) {
        for (InterfaceInfo iface : this.mixin.getSoftImplements()) {
            if (iface.renameMethod(mixinMethod)) {
                method.renameTo(mixinMethod.name);
            }
        }
    }

    protected void prepareField(FieldNode mixinField) {
        // stub
    }
    
    MixinPreProcessorStandard conform(TargetClassContext target) {
        return this.conform(target.getClassInfo());
    }
    
    MixinPreProcessorStandard conform(ClassInfo target) {
        for (MixinMethodNode mixinMethod : this.classNode.mixinMethods) {
            if (mixinMethod.isInjector()) {
                Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
                this.conformInjector(target, mixinMethod, method);
            }
        }
        return this;
    }
    
    private void conformInjector(ClassInfo targetClass, MixinMethodNode mixinMethod, Method method) {
        MethodMapper methodMapper = targetClass.getMethodMapper();
        methodMapper.remapHandlerMethod(this.mixin, mixinMethod, method);
    }

    MixinTargetContext createContextFor(TargetClassContext target) {
        MixinTargetContext context = new MixinTargetContext(this.mixin, this.classNode, target);
        this.conform(target);
        this.attach(context);
        return context;
    }

    /**
     * Run the second pass, attach to the specified context
     * 
     * @param context
     */
    MixinPreProcessorStandard attach(MixinTargetContext context) {
        if (this.attached) {
            throw new IllegalStateException("Preprocessor was already attached");
        }
        
        this.attached = true;
        
        // Perform context-sensitive attachment phase
        this.attachMethods(context);
        this.attachFields(context);
        
        // Apply transformations to the mixin bytecode
        this.transform(context);
        return this;
    }

    protected void attachMethods(MixinTargetContext context) {
        for (Iterator<MixinMethodNode> iter = this.classNode.mixinMethods.iterator(); iter.hasNext();) {
            MixinMethodNode mixinMethod = iter.next();
            
            if (!this.validateMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            if (this.attachInjectorMethod(context, mixinMethod)) {
                continue;
            }
            
            if (this.attachAccessorMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            if (this.attachShadowMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }

            if (this.attachOverwriteMethod(context, mixinMethod)) {
                continue;
            }

            if (this.attachUniqueMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            this.attachMethod(mixinMethod);
        }
    }

    protected boolean validateMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return true;
    }

    protected boolean attachInjectorMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return mixinMethod.isInjector();
    }

    protected boolean attachAccessorMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return this.attachAccessorMethod(context, mixinMethod, Accessor.class) || this.attachAccessorMethod(context, mixinMethod, Invoker.class);
    }

    private boolean attachAccessorMethod(MixinTargetContext context, MixinMethodNode mixinMethod, Class<? extends Annotation> type) {
        AnnotationNode annotation = mixinMethod.getVisibleAnnotation(type);
        if (annotation == null) {
            return false;
        }
        
        Method method = this.getSpecialMethod(mixinMethod, type);
        if (!method.isAbstract()) {
            throw new InvalidAccessorException(context, "@" + ASMHelper.getSimpleName(type) + " method " + mixinMethod.name + " is not abstract");
        }

        if (method.isStatic()) {
            throw new InvalidAccessorException(context, "@" + ASMHelper.getSimpleName(type) + " method " + mixinMethod.name + " cannot be static");
        }
        
        context.addAccessorMethod(mixinMethod, type);
        return true;
    }

    protected boolean attachShadowMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        if (this.attachSpecialMethod(context, mixinMethod, Shadow.class, false)) {
            context.addShadowMethod(mixinMethod);
            return true;
        }
        
        return false;
    }
    
    protected boolean attachOverwriteMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        return this.attachSpecialMethod(context, mixinMethod, Overwrite.class, true);
    }
    
    protected boolean attachSpecialMethod(MixinTargetContext context, MixinMethodNode mixinMethod, Class<? extends Annotation> type,
            boolean overwrite) {
        
        AnnotationNode annotation = mixinMethod.getVisibleAnnotation(type);
        if (annotation == null) {
            return false;
        }
        
        if (overwrite) {
            this.checkMixinNotUnique(mixinMethod, type);
        }
        
        Method method = this.getSpecialMethod(mixinMethod, type);
        MethodNode target = context.findMethod(mixinMethod, annotation);
        if (target == null) {
            if (overwrite) {
                return false;
            }
            target = context.findRemappedMethod(mixinMethod);
            if (target == null) {
                throw new InvalidMixinException(this.mixin, "@" + ASMHelper.getSimpleName(type) + " method " + mixinMethod.name
                        + " was not located in the target class");
            }
            mixinMethod.name = method.renameTo(target.name);
        }
        
        if (Constants.CTOR.equals(target.name)) {
            throw new InvalidMixinException(this.mixin, "Nice try! " + mixinMethod.name + " cannot alias a constructor!");
        }
        
        if (!target.name.equals(mixinMethod.name)) {
            if (!overwrite && (target.access & Opcodes.ACC_PRIVATE) == 0) {
                throw new InvalidMixinException(this.mixin, "Non-private method cannot be aliased. Found " + target.name);
            }
            
            mixinMethod.name = method.renameTo(target.name);
        }
        
        return true;
    }

    protected Method getSpecialMethod(MixinMethodNode mixinMethod, Class<? extends Annotation> type) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        this.checkMethodNotUnique(type, method);
        return method;
    }

    protected void checkMethodNotUnique(Class<? extends Annotation> type, Method method) {
        if (method.isUnique()) {
            String annotation = "@" + ASMHelper.getSimpleName(type);
            throw new InvalidMixinException(this.mixin, annotation + " method " + method.getName() + " cannot be @Unique");
        }
    }

    protected void checkMixinNotUnique(MixinMethodNode mixinMethod, Class<? extends Annotation> type) {
        if (this.mixin.isUnique()) {
            String annotation = "@" + ASMHelper.getSimpleName(type);
            throw new InvalidMixinException(this.mixin, annotation + " method " + mixinMethod.name + " found in a @Unique mixin");
        }
    }

    protected boolean attachUniqueMethod(MixinTargetContext context, MixinMethodNode mixinMethod) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        if (method == null || (!method.isUnique() && !this.mixin.isUnique())) {
            return false;
        }
        
        MethodNode target = context.findMethod(mixinMethod, null);
        if (target == null) {
            return false;
        }
        
        if ((mixinMethod.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            String uniqueName = context.getUniqueName(mixinMethod);
            MixinPreProcessorStandard.logger.log(this.mixin.getLoggingLevel(), "Renaming @Unique method {}{} to {} in {}",
                    mixinMethod.name, mixinMethod.desc, uniqueName, this.mixin);
            mixinMethod.name = method.renameTo(uniqueName);
            return false;
        }

        if (this.strictUnique) {
            throw new InvalidMixinException(this.mixin, "Method conflict, @Unique method " + mixinMethod.name + " in " + this.mixin
                    + " cannot overwrite " + target.name + target.desc + " in " + context.getTarget());
        }
        
        MixinPreProcessorStandard.logger.warn("Discarding @Unique public method {} in {} because it already exists in {}", mixinMethod.name,
                this.mixin, context.getTarget());

        return true;
    }
    
    protected void attachMethod(MixinMethodNode mixinMethod) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod);
        if (method == null) {
            return;
        }
        
        Method parentMethod = this.mixin.getClassInfo().findMethodInHierarchy(mixinMethod, false);
        if (parentMethod != null && parentMethod.isRenamed()) {
            mixinMethod.name = method.renameTo(parentMethod.getName());
        }
    }

    protected void attachFields(MixinTargetContext context) {
        for (Iterator<FieldNode> iter = this.classNode.fields.iterator(); iter.hasNext();) {
            FieldNode mixinField = iter.next();
            AnnotationNode shadow = ASMHelper.getVisibleAnnotation(mixinField, Shadow.class);
            boolean isShadow = shadow != null;
            
            if (!this.validateField(context, mixinField, shadow)) {
                iter.remove();
                continue;
            }
            
            context.transformDescriptor(mixinField);
            
            Field field = this.mixin.getClassInfo().findField(mixinField);
            if (field.isUnique() && isShadow) {
                throw new InvalidMixinException(this.mixin, "@Shadow field " + mixinField.name + " cannot be @Unique");
            }
            
            FieldNode target = context.findField(mixinField, shadow);
            if (target == null) {
                if (shadow == null) {
                    continue;
                }
                target = context.findRemappedField(mixinField);
                if (target == null) {
                    // If this field is a shadow field but is NOT found in the target class, that's bad, mmkay
                    throw new InvalidMixinException(this.mixin, "Shadow field " + mixinField.name + " was not located in the target class");
                }
                mixinField.name = field.renameTo(target.name);
            }
            
            if (field.isUnique()) {
                if ((mixinField.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
                    String uniqueName = context.getUniqueName(mixinField);
                    MixinPreProcessorStandard.logger.log(this.mixin.getLoggingLevel(), "Renaming @Unique field {}{} to {} in {}",
                            mixinField.name, mixinField.desc, uniqueName, this.mixin);
                    mixinField.name = field.renameTo(uniqueName);
                    continue;
                }

                if (this.strictUnique) {
                    throw new InvalidMixinException(this.mixin, "Field conflict, @Unique field " + mixinField.name + " in " + this.mixin
                            + " cannot overwrite " + target.name + target.desc + " in " + context.getTarget());
                }
                
                MixinPreProcessorStandard.logger.warn("Discarding @Unique public field {} in {} because it already exists in {}. "
                        + "Note that declared FIELD INITIALISERS will NOT be removed!", mixinField.name, this.mixin, context.getTarget());

                iter.remove();
                continue;
            }
            
            // Check that the shadow field has a matching descriptor
            if (!target.desc.equals(mixinField.desc)) {
                throw new InvalidMixinException(this.mixin, "The field " + mixinField.name + " in the target class has a conflicting signature");
            }
            
            if (!target.name.equals(mixinField.name)) {
                if ((target.access & Opcodes.ACC_PRIVATE) == 0 && (target.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    throw new InvalidMixinException(this.mixin, "Non-private field cannot be aliased. Found " + target.name);
                }
                
                mixinField.name = field.renameTo(target.name);
            }
            
            // Shadow fields get stripped from the mixin class
            iter.remove();
            
            if (isShadow) {
                boolean isFinal = field.isDecoratedFinal();
                if (this.verboseLogging && ASMHelper.hasFlag(target, Opcodes.ACC_FINAL) != isFinal) {
                    String message = isFinal
                        ? "@Shadow field {}::{} is decorated with @Final but target is not final"
                        : "@Shadow target {}::{} is final but shadow is not decorated with @Final";
                    MixinPreProcessorStandard.logger.warn(message, this.mixin, mixinField.name);
                }

                context.addShadowField(mixinField, field);
            }
        }
    }

    protected boolean validateField(MixinTargetContext context, FieldNode field, AnnotationNode shadow) {
        // Public static fields will fall foul of early static binding in java, including them in a mixin is an error condition
        if (ASMHelper.hasFlag(field, Opcodes.ACC_STATIC)
                && !ASMHelper.hasFlag(field, Opcodes.ACC_PRIVATE)
                && !ASMHelper.hasFlag(field, Opcodes.ACC_SYNTHETIC)
                && shadow == null) {
            throw new InvalidMixinException(context, String.format("Mixin %s contains non-private static field %s:%s",
                    context, field.name, field.desc));
        }

        // Shadow fields can't have prefixes, it's meaningless for them anyway
        String prefix = ASMHelper.<String>getAnnotationValue(shadow, "prefix", Shadow.class);
        if (field.name.startsWith(prefix)) {
            throw new InvalidMixinException(context, String.format("@Shadow field %s.%s has a shadow prefix. This is not allowed.",
                    context, field.name));
        }
        
        // Imaginary super fields get stripped from the class, but first we validate them
        if (Constants.IMAGINARY_SUPER.equals(field.name)) {
            if (field.access != Opcodes.ACC_PRIVATE) {
                throw new InvalidMixinException(this.mixin, "Imaginary super field " + context + "." + field.name
                        + " must be private and non-final");
            }
            if (!field.desc.equals("L" + this.mixin.getClassRef() + ";")) {
                throw new InvalidMixinException(this.mixin, "Imaginary super field " + context + "." + field.name
                        + " must have the same type as the parent mixin");
            }
            return false;
        }
        
        return true;
    }

    /**
     * Apply discovered method and field renames to method invocations and field
     * accesses in the mixin
     */
    protected void transform(MixinTargetContext context) {
        for (MethodNode mixinMethod : this.classNode.methods) {
            for (Iterator<AbstractInsnNode> iter = mixinMethod.instructions.iterator(); iter.hasNext();) {
                AbstractInsnNode insn = iter.next();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode methodNode = (MethodInsnNode)insn;
                    Method method = ClassInfo.forName(methodNode.owner).findMethodInHierarchy(methodNode, true, ClassInfo.INCLUDE_PRIVATE);
                    if (method != null && method.isRenamed()) {
                        methodNode.name = method.getName();
                    }
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fieldNode = (FieldInsnNode)insn;
                    Field field = ClassInfo.forName(fieldNode.owner).findField(fieldNode, ClassInfo.INCLUDE_PRIVATE);
                    if (field != null && field.isRenamed()) {
                        fieldNode.name = field.getName();
                    }
                }
            }
        }
    }

}
