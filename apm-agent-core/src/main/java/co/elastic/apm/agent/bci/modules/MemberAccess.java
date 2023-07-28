package co.elastic.apm.agent.bci.modules;

import co.elastic.apm.agent.common.JvmRuntimeInfo;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public abstract class MemberAccess {

    private static final MemberAccess INSTANCE;

    private static Instrumentation instrumentation;

    static {
        if (JvmRuntimeInfo.ofCurrentVM().getMajorVersion() >= 9) {
            try {
                INSTANCE = (MemberAccess) Class.forName("co.elastic.apm.agent.bci.modules.MemberAccess$ModulesMemberAccess").getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            INSTANCE = new NoModulesMemberAccess();
        }
    }

    public static void setInstrumentation(Instrumentation instr) {
        instrumentation = instr;
    }

    public static MethodHandle method(Class<?> declaringClass, String name, Class<?>... parameterTypes) {
        try {
            Method method = declaringClass.getDeclaredMethod(name, parameterTypes);
            return INSTANCE.invoker(method);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static MethodHandle fieldGetter(Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            return INSTANCE.unreflectGetter(field);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static MethodHandle fieldSetter(Class<?> declaringClass, String name) {
        try {
            Field field = declaringClass.getDeclaredField(name);
            return INSTANCE.unreflectSetter(field);
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(e);
        }
    }

    abstract MethodHandle invoker(Method method);

    abstract MethodHandle unreflectGetter(Field field);

    abstract MethodHandle unreflectSetter(Field field);


    public static class NoModulesMemberAccess extends MemberAccess {

        @Override
        public MethodHandle invoker(Method method) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            boolean prevFlag = method.isAccessible();
            method.setAccessible(true);
            try {
                return lookup.unreflect(method);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Should never happen", e);
            } finally {
                method.setAccessible(prevFlag);
            }
        }

        @Override
        public MethodHandle unreflectGetter(Field field) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            boolean prevFlag = field.isAccessible();
            field.setAccessible(true);
            try {
                return lookup.unreflectGetter(field);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Should never happen", e);
            } finally {
                field.setAccessible(prevFlag);
            }
        }

        @Override
        public MethodHandle unreflectSetter(Field field) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            boolean prevFlag = field.isAccessible();
            field.setAccessible(true);
            try {
                return lookup.unreflectSetter(field);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Should never happen", e);
            } finally {
                field.setAccessible(prevFlag);
            }
        }
    }

    public static class ModulesMemberAccess extends MemberAccess {

        @Override
        public MethodHandle invoker(Method method) {
            try {
                return getPrivateLookup(method.getDeclaringClass()).unreflect(method);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Should never happen due to module being opened", e);
            }
        }


        @Override
        public MethodHandle unreflectGetter(Field field) {
            try {
                return getPrivateLookup(field.getDeclaringClass()).unreflectGetter(field);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Should never happen due to module being opened", e);
            }
        }

        @Override
        public MethodHandle unreflectSetter(Field field) {
            try {
                return getPrivateLookup(field.getDeclaringClass()).unreflectSetter(field);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Should never happen due to module being opened", e);
            }
        }

        private static MethodHandles.Lookup getPrivateLookup(Class<?> clazz) throws IllegalAccessException {
            String packageName = clazz.getPackageName();
            ModuleOpener.getInstance().openModuleTo(instrumentation, clazz, ModulesMemberAccess.class.getClassLoader(), Arrays.asList(packageName));
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            return lookup;
        }
    }
}
