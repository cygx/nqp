package org.perl6.nqp.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.perl6.nqp.sixmodel.STable;
import org.perl6.nqp.sixmodel.SixModelObject;
import org.perl6.nqp.sixmodel.StorageSpec;
import org.perl6.nqp.sixmodel.reprs.JavaObjectWrapper;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Factory for Java object interop wrappers.  This class is designed to be subclassed by HLLs.  Not shareable between {@link GlobalContext}s. */
public class BootJavaInterop {

    /** Set this to a non-null value to use the same STable for every class. */
    protected STable commonSTable;

    /** The global context that this interop factory is used for. */
    protected GlobalContext gc;

    public BootJavaInterop(GlobalContext gc) {
        this.gc = gc;
        commonSTable = gc.BOOTJava.st;
    }

    private static class InteropInfo {
        public Class<?> forClass;
        public SixModelObject interop;
        public STable stable; // not used if commonSTable != null
    }

    private ClassValue<InteropInfo> cache = new ClassValue<InteropInfo>() {
        @Override public InteropInfo computeValue(Class<?> cl) {
            InteropInfo r = new InteropInfo();
            r.forClass = cl;
            r.interop = computeInterop(cl);
            r.stable = computeSTable(cl, r.interop);
            return r;
        }
    };

    /** Override this to define per-class STables.  <b>Will not be used unless you set {@link commonSTable} to null in the constructor.</b> */
    protected STable computeSTable(Class<?> klass, SixModelObject interop) {
        return null;
    }

    /** Get STable for class, computing if necessary. */
    public STable getSTableForClass(Class<?> c) {
        return commonSTable != null ? commonSTable : cache.get(c).stable;
    }

    /** Get interop table for a class. */
    public SixModelObject getInterop(Class<?> c) {
        return cache.get(c).interop;
    }

    /** Entry point for callouts. */
    public SixModelObject getInterop(SixModelObject to, ThreadContext tc) {
        if (to instanceof JavaObjectWrapper) {
            Object o = ((JavaObjectWrapper)to).theObject;
            if (o instanceof Class<?>) return getInterop((Class<?>)o);
        }
        try {
            return getInterop(Class.forName(Ops.unbox_s(to, tc), false, BootJavaInterop.class.getClassLoader()));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /** Entry point for callback setup. */
    public SixModelObject implementClass(SixModelObject description, ThreadContext tc) {
        throw new UnsupportedOperationException();
    }

    // begin gory details
    protected SixModelObject computeInterop(Class<?> klass) {
        ThreadContext tc = gc.getCurrentThreadContext();

        ClassContext adaptor = createAdaptor(klass);

        CompilationUnit adaptorUnit;
        try {
            adaptor.constructed.getField("constants").set(null, adaptor.constants.toArray(new Object[0]));
            adaptorUnit = (CompilationUnit) adaptor.constructed.newInstance();
            adaptorUnit.initializeCompilationUnit(tc);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }

        SixModelObject hash = gc.BOOTHash.st.REPR.allocate(tc, gc.BOOTHash.st);

        for (int i = 0; i < adaptor.descriptors.size(); i++)
            hash.bind_key_boxed(tc, adaptor.descriptors.get(i), adaptorUnit.lookupCodeRef(i));

        return hash;
    }

    protected ClassContext createAdaptor(Class<?> target) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String className = "org/perl6/nqp/generatedadaptor/"+target.getName().replace('.','/');
        cw.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, TYPE_CU.getInternalName(), null);

        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC, "constants", "[Ljava/lang/Object;", null, null).visitEnd();

        ClassContext cc = new ClassContext();
        cc.cv = cw;
        cc.className = className;
        cc.target = target;

        for (Method m : target.getMethods()) createAdaptorMethod(cc, m);
        for (Field f : target.getFields()) createAdaptorField(cc, f);
        for (Constructor<?> c : target.getConstructors()) createAdaptorConstructor(cc, c);
        createAdaptorSpecials(cc);

        MethodVisitor mv;
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getCallSites", "()[Lorg/perl6/nqp/runtime/CallSiteDescriptor;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0,0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "hllName", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0,0);
        mv.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perl6/nqp/runtime/CompilationUnit", "<init>", "()V");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0,0);
        mv.visitEnd();

        cw.visitEnd();
        byte[] bits = cw.toByteArray();
        //try {
        //    java.nio.file.Files.write(new java.io.File(className.replace('/','_') + ".class").toPath(), bits);
        //} catch (java.io.IOException e) {
        //    e.printStackTrace();
        //}
        cc.constructed = new ByteClassLoader(bits).findClass(className.replace('/','.'));
        return cc;
    }

    protected void createAdaptorMethod(ClassContext c, Method tobind) {
        Class<?>[] ptype = tobind.getParameterTypes();
        boolean isStatic = Modifier.isStatic(tobind.getModifiers());

        String desc = Type.getMethodDescriptor(tobind);
        MethodContext cc = startCallout(c, ptype.length + (isStatic ? 0 : 1),
                (isStatic ? "static_method:" : "method:") + tobind.getName() + desc);

        int parix = 0;
        preMarshalIn(cc, tobind.getReturnType(), 0);
        if (!isStatic) marshalOut(cc, tobind.getDeclaringClass(), parix++);
        for (Class<?> pt : ptype) marshalOut(cc, pt, parix++);
        cc.mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, Type.getInternalName(tobind.getDeclaringClass()), tobind.getName(), desc);
        marshalIn(cc, tobind.getReturnType(), 0);

        endCallout(cc);
    }

    protected void createAdaptorField(ClassContext c, Field f) {
        boolean isStatic = Modifier.isStatic(f.getModifiers());
        MethodContext cc;

        cc = startCallout(c, isStatic ? 0 : 1, (isStatic ? "getstatic:" : "getfield:") + ":" + f.getName() + ";" + Type.getDescriptor(f.getType()));
        preMarshalIn(cc, f.getType(), 0);
        if (!isStatic) marshalOut(cc, f.getDeclaringClass(), 0);
        cc.mv.visitFieldInsn(isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD, Type.getInternalName(f.getDeclaringClass()), f.getName(), Type.getDescriptor(f.getType()));
        marshalIn(cc, f.getType(), 0);
        endCallout(cc);

        if (!Modifier.isFinal(f.getModifiers())) {
            cc = startCallout(c, isStatic ? 1 : 2, (isStatic ? "putstatic:" : "putfield:") + ":" + f.getName() + ";" + Type.getDescriptor(f.getType()));
            preMarshalIn(cc, void.class, 0);
            if (!isStatic) marshalOut(cc, f.getDeclaringClass(), 0);
            marshalOut(cc, f.getType(), isStatic ? 0 : 1);
            cc.mv.visitFieldInsn(isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD, Type.getInternalName(f.getDeclaringClass()), f.getName(), Type.getDescriptor(f.getType()));
            marshalIn(cc, void.class, 0);
            endCallout(cc);
        }
    }

    protected void createAdaptorConstructor(ClassContext c, Constructor<?> k) {
        Class<?>[] ptypes = k.getParameterTypes();
        String desc = Type.getConstructorDescriptor(k);
        MethodContext cc = startCallout(c, ptypes.length, "constructor:"+desc);
        int parix = 0;
        preMarshalIn(cc, k.getDeclaringClass(), 0);
        cc.mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(k.getDeclaringClass()));
        cc.mv.visitInsn(Opcodes.DUP);
        for (Class<?> p : ptypes) marshalOut(cc, p, parix++);
        cc.mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(k.getDeclaringClass()), "<init>", desc);
        marshalIn(cc, k.getDeclaringClass(), 0);
        endCallout(cc);
    }

    protected void createAdaptorSpecials(ClassContext c) {
        // odds and ends like early bound array stuff, isinst, nondefault marshalling...
        MethodContext cc = startCallout(c, 1, "box");
        preMarshalIn(cc, Object.class, 0);
        marshalOut(cc, c.target, 0);
        // implicit widening conversion to Object
        marshalIn(cc, Object.class, 0);
        endCallout(cc);

        cc = startCallout(c, 1, "unbox");
        preMarshalIn(cc, c.target, 0);
        marshalOut(cc, Object.class, 0);
        cc.mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(c.target));
        marshalIn(cc, c.target, 0);
        endCallout(cc);

        cc = startCallout(c, 1, "isinst");
        preMarshalIn(cc, boolean.class, 0);
        marshalOut(cc, Object.class, 0);
        cc.mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(c.target));
        marshalIn(cc, boolean.class, 0);
        endCallout(cc);
    }

    /** Override this to customize marshalling. */
    protected int storageForType(Class<?> what) {
        int ty;
        if (what == String.class || what == char.class)
            return StorageSpec.BP_STR;
        else if (what == float.class || what == double.class)
            return StorageSpec.BP_NUM;
        else if (what != void.class && what.isPrimitive())
            return StorageSpec.BP_INT;
        else
            return StorageSpec.BP_NONE;
    }

    /** Override this to customize marshalling. */
    protected void preMarshalIn(MethodContext c, Class<?> what, int ix) {
        preEmitPutToNQP(c, ix, storageForType(what));
    }

    /** Override this to customize marshalling. */
    protected void marshalIn(MethodContext c, Class<?> what, int ix) {
        if (what == void.class) {
            c.mv.visitInsn(Opcodes.ACONST_NULL);
        }
        else if (what == int.class || what == short.class || what == byte.class || what == boolean.class) {
            c.mv.visitInsn(Opcodes.I2L);
        }
        else if (what == long.class || what == double.class || what == String.class) {
            // already in needed form
        }
        else if (what == float.class) {
            c.mv.visitInsn(Opcodes.F2D);
        }
        else if (what == char.class) {
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(C)Ljava/lang/String;");
        }
        else {
            if (commonSTable != null) {
                emitConst(c, commonSTable, STable.class);
            } else {
                emitConst(c, new STableCache(what), STableCache.class);
                c.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(STableCache.class), "getSTable", "()Lorg/perl6/nqp/sixmodel/STable;");
            }
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perl6/nqp/runtime/BootJavaInterop$RuntimeSupport", "boxJava", Type.getMethodDescriptor(TYPE_SMO, TYPE_OBJ, TYPE_ST));
        }

        emitPutToNQP(c, ix, storageForType(what));
    }

    protected void marshalOut(MethodContext c, Class<?> what, int ix) {
        emitGetFromNQP(c, ix, storageForType(what));

        if (what == void.class) {
            c.mv.visitInsn(Opcodes.POP);
        }
        else if (what == long.class || what == double.class || what == String.class) {
            // already in needed form
        }
        else if (what == int.class || what == short.class || what == byte.class || what == boolean.class) {
            c.mv.visitInsn(Opcodes.L2I);
            if (what == short.class) c.mv.visitInsn(Opcodes.I2S);
            else if (what == byte.class) c.mv.visitInsn(Opcodes.I2B);
            else if (what == boolean.class) {
                Label f = new Label(), e = new Label(); // ugh, but this is what javac does for != 0
                c.mv.visitJumpInsn(Opcodes.IFEQ, f);
                c.mv.visitInsn(Opcodes.ICONST_1);
                c.mv.visitJumpInsn(Opcodes.GOTO, e);
                c.mv.visitLabel(f);
                c.mv.visitInsn(Opcodes.ICONST_0);
                c.mv.visitLabel(e);
            }
        }
        else if (what == float.class)
            c.mv.visitInsn(Opcodes.D2F);
        else if (what == char.class) {
            c.mv.visitInsn(Opcodes.ICONST_0);
            c.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C");
        }
        else {
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perl6/nqp/runtime/BootJavaInterop$RuntimeSupport", "unboxJava", Type.getMethodDescriptor(TYPE_OBJ, TYPE_SMO));
            c.mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(what));
        }
    }

    protected static final char[] TYPE_CHAR = new char[] { 'o', 'i', 'n', 's' };
    protected static final Type[] TYPES = new Type[] { Type.getType(SixModelObject.class), Type.LONG_TYPE, Type.DOUBLE_TYPE, Type.getType(String.class) };
    protected static final Type TYPE_AOBJ = Type.getType(Object[].class);
    protected static final Type TYPE_CF  = Type.getType(CallFrame.class);
    protected static final Type TYPE_CR = Type.getType(CodeRef.class);
    protected static final Type TYPE_CSD = Type.getType(CallSiteDescriptor.class);
    protected static final Type TYPE_CU  = Type.getType(CompilationUnit.class);
    protected static final Type TYPE_OBJ = Type.getType(Object.class);
    protected static final Type TYPE_OPS = Type.getType(Ops.class);
    protected static final Type TYPE_SMO = Type.getType(SixModelObject.class);
    protected static final Type TYPE_ST = Type.getType(STable.class);
    protected static final Type TYPE_TC = Type.getType(ThreadContext.class);

    protected static class ClassContext {
        public ClassVisitor cv;
        public String className;
        public List<Object> constants = new ArrayList< >();
        public Class<?> target, constructed;
        public List<String> descriptors = new ArrayList< >();
        public int nextCallout;
    }

    protected MethodContext startCallout(ClassContext cc, int arity, String desc) {
        MethodContext mc = new MethodContext();
        mc.cc = cc;
        MethodVisitor mv = mc.mv = cc.cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "qb_"+(cc.nextCallout++),
                Type.getMethodDescriptor(Type.VOID_TYPE, TYPE_CU, TYPE_TC, TYPE_CR, TYPE_CSD, TYPE_AOBJ),
                null, null);
        AnnotationVisitor av = mv.visitAnnotation("Lorg/perl6/nqp/runtime/CodeRefAnnotation;", true);
        av.visit("name", "callout "+cc.target.getName()+" "+desc);
        av.visitEnd();
        mv.visitCode();
        cc.descriptors.add(desc);

        mc.argsLoc = 4;
        mc.csdLoc = 3;
        mc.cfLoc = 5;

        mv.visitTypeInsn(Opcodes.NEW, "org/perl6/nqp/runtime/CallFrame");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // tc
        mv.visitVarInsn(Opcodes.ALOAD, 2); // cr
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perl6/nqp/runtime/CallFrame", "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, TYPE_TC, TYPE_CR));
        mv.visitVarInsn(Opcodes.ASTORE, 5); // cf;

        mv.visitLabel(mc.tryStart = new Label());

        mv.visitVarInsn(Opcodes.ALOAD, 5); // cf
        mv.visitVarInsn(Opcodes.ALOAD, 3); // csd
        mv.visitVarInsn(Opcodes.ALOAD, 4); // args
        emitInteger(mc, arity);
        emitInteger(mc, arity);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "checkarity", Type.getMethodDescriptor(TYPE_CSD, TYPE_CF, TYPE_CSD, TYPE_AOBJ, Type.INT_TYPE, Type.INT_TYPE));
        mv.visitVarInsn(Opcodes.ASTORE, 3); // csd
        mv.visitVarInsn(Opcodes.ALOAD, 1); // tc
        mv.visitFieldInsn(Opcodes.GETFIELD, TYPE_TC.getInternalName(), "flatArgs", TYPE_AOBJ.getDescriptor());
        mv.visitVarInsn(Opcodes.ASTORE, 4); // args

        return mc;
    }

    protected void endCallout(MethodContext c) {
        MethodVisitor mv = c.mv;
        Label endTry = new Label();
        Label handler = new Label();
        Label notcontrol = new Label();

        mv.visitLabel(endTry);
        mv.visitVarInsn(Opcodes.ALOAD, 5); //cf
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TYPE_CF.getInternalName(), "leave", "()V");
        mv.visitInsn(Opcodes.RETURN);

        mv.visitLabel(handler);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/perl6/nqp/runtime/ControlException");
        mv.visitJumpInsn(Opcodes.IFEQ, notcontrol);
        mv.visitVarInsn(Opcodes.ALOAD, 5); //cf
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TYPE_CF.getInternalName(), "leave", "()V");
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(notcontrol);
        mv.visitVarInsn(Opcodes.ALOAD, 1); // tc
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perl6/nqp/runtime/ExceptionHandling", "dieInternal",
                Type.getMethodDescriptor(Type.getType(RuntimeException.class), TYPE_TC, Type.getType(Throwable.class)));
        mv.visitInsn(Opcodes.ATHROW);

        c.mv.visitTryCatchBlock(c.tryStart, endTry, handler, null);
        c.mv.visitMaxs(0,0);
        c.mv.visitEnd();
    }

    protected static class MethodContext {
        public ClassContext cc;
        public MethodVisitor mv;
        public boolean callback;
        public int cfLoc;
        public int argsLoc;
        public int csdLoc;
        public Label tryStart;
    }

    protected void emitInteger(MethodContext c, int i) {
        if (i >= -1 && i <= 5) c.mv.visitInsn(Opcodes.ICONST_0 + i);
        else if (i == (byte)i) c.mv.visitIntInsn(Opcodes.BIPUSH, i);
        else if (i == (short)i) c.mv.visitIntInsn(Opcodes.SIPUSH, i);
        else c.mv.visitLdcInsn(i);
    }

    protected <T> void emitConst(MethodContext c, T k, Class<T> cls) {
        List<Object> ks = c.cc.constants;
        int kix = ks.size();
        ks.add(k);
        c.mv.visitFieldInsn(Opcodes.GETSTATIC, c.cc.className, "constants", "[Ljava/lang/Object;");
        emitInteger(c, kix);
        c.mv.visitInsn(Opcodes.AALOAD);
        if (cls != Object.class) c.mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(cls));
    }

    protected void emitGetFromNQP(MethodContext c, int index, int type) {
        if (c.callback) {
            // return value
            c.mv.visitVarInsn(Opcodes.ALOAD, c.cfLoc);
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "result_"+TYPE_CHAR[type], Type.getMethodDescriptor(TYPES[type], TYPE_CF));
        } else {
            // an argument
            c.mv.visitVarInsn(Opcodes.ALOAD, c.cfLoc);
            c.mv.visitVarInsn(Opcodes.ALOAD, c.csdLoc);
            c.mv.visitVarInsn(Opcodes.ALOAD, c.argsLoc);
            emitInteger(c, index);
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "posparam_"+TYPE_CHAR[type], Type.getMethodDescriptor(TYPES[type], TYPE_CF, TYPE_CSD, TYPE_AOBJ, Type.INT_TYPE));
        }
    }

    protected void preEmitPutToNQP(MethodContext c, int index, int type) {
        if (c.callback) {
            // an argument
            c.mv.visitVarInsn(Opcodes.ALOAD, c.argsLoc);
            emitInteger(c, index);
        }
    }

    protected void emitPutToNQP(MethodContext c, int index, int type) {
        if (c.callback) {
            // an argument
            if (type == StorageSpec.BP_INT) {
                c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
            } else if (type == StorageSpec.BP_NUM) {
                c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double");
            }
            c.mv.visitInsn(Opcodes.AASTORE);
        } else {
            c.mv.visitVarInsn(Opcodes.ALOAD, c.cfLoc);
            c.mv.visitMethodInsn(Opcodes.INVOKESTATIC, TYPE_OPS.getInternalName(), "return_"+TYPE_CHAR[type], Type.getMethodDescriptor(Type.VOID_TYPE, TYPES[type], TYPE_CF));
        }
    }

    /** No user-servicable parts inside.  Public for the sake of generated code only. */
    public static class RuntimeSupport {
        public static SixModelObject boxJava(Object o, STable st) {
            JavaObjectWrapper jow = new JavaObjectWrapper();
            jow.st = st;
            jow.theObject = o;
            return jow;
        }

        public static Object unboxJava(SixModelObject smo) {
            return ((JavaObjectWrapper)smo).theObject;
        }
    }

    public class STableCache {
        private volatile STable localCache;
        private Class<?> what;

        public STableCache(Class<?> what) { this.what = what; }
        public STable getSTable() {
            STable fetch = localCache;
            if (fetch != null) return fetch;
            return localCache = getSTableForClass(what);
        }
    }
}
