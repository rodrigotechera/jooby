package jooby.internal.mvc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class DefaultParamResolver implements ParamResolver {

  private static class AsmResolver {

    private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

    public List<String> collect(final Executable executable, final boolean reload)
        throws Exception {
      Class<?> owner = executable.getDeclaringClass();
      String key = key(owner, executable);

      if (!reload) {
        List<String> names = cache.get(key);
        if (names != null) {
          return names;
        }
      }
      // cache if off or we must reload
      ClassReader reader = new ClassReader(owner.getName());
      reader.accept(new ClassVisitor(Opcodes.ASM5) {
        @Override
        public MethodVisitor visitMethod(final int access, final String name, final String desc,
            final String signature,
            final String[] exceptions) {
          String key = key(owner, name, desc);
          cache.put(key, new ArrayList<>());
          return new MethodVisitor(Opcodes.ASM5) {
            @Override
            public void visitLocalVariable(final String name, final String desc,
                final String signature, final Label start, final Label end, final int index) {
              if (!"this".equals(name)) {
                cache.get(key).add(name);
              }
            }
          };
        }
      }, 0);
      return cache.get(key);
    }

    private static String key(final Class<?> clazz, final Executable executable) {
      @SuppressWarnings("rawtypes")
      String desc = executable instanceof Method
          ?  Type.getMethodDescriptor((Method) executable)
          : Type.getConstructorDescriptor((Constructor) executable);
      return key(clazz, executable.getName(), desc);
    }

    private static String key(final Class<?> clazz, final String name, final String descriptor) {
      return clazz.getName() + "." + name + "#" + descriptor;
    }
  }

  private boolean reload;

  private final AsmResolver resolver = new AsmResolver();

  public DefaultParamResolver(final boolean reload) {
    this.reload = reload;
  }

  @Override
  public List<ParamValue> resolve(final Method method) throws Exception {
    Method target = reload ? reload(method) : method;
    if (target.getParameterCount() == 0) {
      return Collections.emptyList();
    }
    Parameter[] parameters = target.getParameters();
    List<ParamValue> params = new ArrayList<>(parameters.length);
    for (Parameter parameter : parameters) {
      if (parameter.isNamePresent()) {
        params.add(new ParamValue(new ParamDef(parameter)));
      }
    }
    if (params.size() > 0) {
      return params;
    }
    // fallback and use ASM
    List<String> names = resolver.collect(method, reload);
    for (int idx = 0; idx < parameters.length; idx++) {
      Parameter parameter = parameters[idx];
      params.add(new ParamValue(new ParamDef(names.get(idx), parameter.getType(), parameter
          .getParameterizedType(), parameter.getAnnotations())));
    }
    return params;
  }

  private Method reload(final Method method) throws Exception {
    Class<?> owner = method.getDeclaringClass();
    return owner.getDeclaredMethod(method.getName(), method.getParameterTypes());
  }

}