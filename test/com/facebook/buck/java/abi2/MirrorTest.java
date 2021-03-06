/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java.abi2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.zip.Unzip;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.SortedSet;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;


public class MirrorTest {

  private static final ImmutableSortedSet<Path> EMPTY_CLASSPATH = ImmutableSortedSet.of();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private Path stubJar;

  @Before
  public void createStubJar() throws IOException {
    File out = temp.newFolder();
    stubJar = out.toPath().resolve("stub.jar").toAbsolutePath();
  }

  @Test
  public void emptyClass() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        "package com.example.buck; public class A {}");

    new StubJar(jar).writeTo(stubJar);

    // Verify that the stub jar works by compiling some code that depends on A.
    compileToJar(
        ImmutableSortedSet.of(stubJar),
        "B.java",
        "package com.example.buck; public class B extends A {}");
  }

  @Test
  public void emptyClassWithAnnotation() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        "package com.example.buck; @Deprecated public class A {}");

    new StubJar(jar).writeTo(stubJar);

    // Examine the jar to see if the "A" class is deprecated.
    ClassNode classNode = readClass(stubJar, "com/example/buck/A.class");
    assertNotEquals(0, classNode.access & Opcodes.ACC_DEPRECATED);
  }

  @Test
  public void classWithTwoMethods() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(ImmutableList.of(
            "package com.example.buck;",
            "public class A {",
            "  public String toString() { return null; }",
            "  public void eatCake() {}",
            "}")));

    new StubJar(jar).writeTo(stubJar);

    // Verify that both methods are present and given in alphabetical order.
    ClassNode classNode = readClass(stubJar, "com/example/buck/A.class");
    List<MethodNode> methods = classNode.methods;
    // Index 0 is the <init> method. Skip that.
    assertEquals("eatCake", methods.get(1).name);
    assertEquals("toString", methods.get(2).name);
  }

  @Test
  public void genericClassSignaturesShouldBePreserved() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T get(String key) { return null; }",
                "}"
            )));

    // With generic classes, there are typically two interesting things we want to keep an eye on.
    // First is the "descriptor", which is the signature of the method with type erasure complete.
    // Optionally, compilers (and the OpenJDK, Oracle and Eclipse compilers all do this) can also
    // include a "signature", which is the signature of the method before type erasure. See
    // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3 for more.
    ClassNode original = readClass(jar, "com/example/buck/A.class");
    String classSig = original.signature;
    MethodNode originalGet = findMethod(original, "get");

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    assertEquals(classSig, stubbed.signature);

    MethodNode stubbedGet = findMethod(stubbed, "get");
    assertMethodEquals(originalGet, stubbedGet);
  }

  @Test
  public void shouldIgnorePrivateMethods() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  private void privateMethod() {}",
                "  void packageMethod() {}",
                "  protected void protectedMethod() {}",
                "  public void publicMethod() {}",
                "}"
            )));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    for (MethodNode method : stubbed.methods) {
      assertFalse(method.name.contains("private"));
    }
  }

  @Test
  public void shouldPreserveAField() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  protected String protectedField;",
                "}"
            )));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    FieldNode field = stubbed.fields.get(0);
    assertEquals("protectedField", field.name);
    assertTrue((field.access & Opcodes.ACC_PROTECTED) > 0);
  }

  @Test
  public void shouldIgnorePrivateFields() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  private String privateField;",
                "}"
            )));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    assertEquals(0, stubbed.fields.size());
  }

  @Test
  public void shouldPreserveGenericTypesOnFields() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T theField;",
                "}")));

    new StubJar(jar).writeTo(stubJar);

    ClassNode original = readClass(jar, "com/example/buck/A.class");
    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");

    FieldNode originalField = original.fields.get(0);
    FieldNode stubbedField = stubbed.fields.get(0);

    assertFieldEquals(originalField, stubbedField);
  }

  @Test
  public void shouldPreserveGenericTypesOnMethods() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A<T> {",
                "  public T get(String key) { return null; }",
                "  public <X extends Comparable<T>> X compareWith(T other) { return null; }",
                "}")));

    new StubJar(jar).writeTo(stubJar);

    ClassNode original = readClass(jar, "com/example/buck/A.class");
    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");

    MethodNode originalGet = findMethod(original, "get");
    MethodNode stubbedGet = findMethod(stubbed, "get");

    assertEquals(originalGet.signature, stubbedGet.signature);
    assertEquals(originalGet.desc, stubbedGet.desc);

    MethodNode originalCompare = findMethod(original, "compareWith");
    MethodNode stubbedCompare = findMethod(stubbed, "compareWith");

    assertEquals(originalCompare.signature, stubbedCompare.signature);
    assertEquals(originalCompare.desc, stubbedCompare.desc);
  }

  @Test
  public void preservesAnnotationsOnMethods() throws IOException {
    Path annotations = buildAnnotationJar();
    Path jar = compileToJar(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  @Foo",
                "  public void cheese(String key) {}",
                "}")));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    MethodNode method = findMethod(stubbed, "cheese");

    List<AnnotationNode> seen = method.visibleAnnotations;
    assertEquals(1, seen.size());
    assertEquals("Lcom/example/buck/Foo;", seen.get(0).desc);
  }

  @Test
  public void preservesAnnotationsOnFields() throws IOException {
    Path annotations = buildAnnotationJar();
    Path jar = compileToJar(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  @Foo",
                "  public String name;",
                "}")));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    FieldNode field = findField(stubbed, "name");

    List<AnnotationNode> seen = field.visibleAnnotations;
    assertEquals(1, seen.size());
    assertEquals("Lcom/example/buck/Foo;", seen.get(0).desc);
  }

  @Test
  public void preservesAnnotationsOnParameters() throws IOException {
    Path annotations = buildAnnotationJar();
    Path jar = compileToJar(
        ImmutableSortedSet.of(annotations),
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public void peynir(@Foo String very, int tasty) {}",
                "}")));

    new StubJar(jar).writeTo(stubJar);

    ClassNode stubbed = readClass(stubJar, "com/example/buck/A.class");
    MethodNode method = findMethod(stubbed, "peynir");

    List<AnnotationNode>[] parameterAnnotations = method.visibleParameterAnnotations;
    assertEquals(2, parameterAnnotations.length);
  }

  @Test
  public void stubsInnerClasses() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public class B {",
                "    public int count;",
                "    public void foo() {}",
                "  }",
                "}"
            )));

    new StubJar(jar).writeTo(stubJar);

    ClassNode original = readClass(jar, "com/example/buck/A$B.class");
    ClassNode stubbed = readClass(stubJar, "com/example/buck/A$B.class");

    MethodNode originalFoo = findMethod(original, "foo");
    MethodNode stubbedFoo = findMethod(stubbed, "foo");
    assertMethodEquals(originalFoo, stubbedFoo);

    FieldNode originalCount = findField(original, "count");
    FieldNode stubbedCount = findField(stubbed, "count");
    assertFieldEquals(originalCount, stubbedCount);
  }

  @Test
  public void abiSafeChangesResultInTheSameOutputJar() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  protected final static int count = 42;",
                "  public String getGreeting() { return \"hello\"; }",
                "  Class<?> clazz;",
                "  public int other;",
                "}"
            )));

    new StubJar(jar).writeTo(stubJar);
    HashCode originalHash =
        com.google.common.io.Files.asByteSource(stubJar.toFile()).hash(Hashing.sha1());

    Path jar2 = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(
            ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  Class<?> clazz = String.class;",
                "  public String getGreeting() { return \"merhaba\"; }",
                "  protected final static int count = 42;",
                "  public int other = 32;",
                "}"
            )));
    Files.delete(stubJar);
    new StubJar(jar2).writeTo(stubJar);
    HashCode secondHash =
        com.google.common.io.Files.asByteSource(stubJar.toFile()).hash(Hashing.sha1());

    assertEquals(originalHash, secondHash);
  }

  private Path compileToJar(
      SortedSet<Path> classpath,
      String fileName,
      String source) throws IOException {
    File inputs = temp.newFolder();

    File file = new File(inputs, fileName);

    Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> sourceObjects =
        fileManager.getJavaFileObjectsFromFiles(ImmutableSet.of(file));

    final File outputDir = temp.newFolder();
    List<String> args = Lists.newArrayList("-g", "-d", outputDir.getAbsolutePath());

    if (!classpath.isEmpty()) {
      args.add("-classpath");
      args.add(Joiner.on(File.pathSeparator).join(classpath));
    }

    JavaCompiler.CompilationTask compilation =
        compiler.getTask(null, fileManager, null, args, null, sourceObjects);

    Boolean result = compilation.call();

    fileManager.close();
    assertNotNull(result);
    assertTrue(result);

    File jar = new File(outputDir, "output.jar");

    try (
        FileOutputStream fos = new FileOutputStream(jar);
        final JarOutputStream os = new JarOutputStream(fos)) {
      SimpleFileVisitor<Path> visitor = new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (file.getFileName().toString().endsWith(".class")) {
            ZipEntry entry = new ZipEntry(outputDir.toPath().relativize(file).toString());
            os.putNextEntry(entry);
            ByteStreams.copy(Files.newInputStream(file), os);
            os.closeEntry();
          }
          return FileVisitResult.CONTINUE;
        }
      };

      Files.walkFileTree(outputDir.toPath(), visitor);
    }

    return jar.toPath().toAbsolutePath();
  }

  @Test
  public void stubJarIsEquallyAtHomeWalkingADirectoryOfClassFiles() throws IOException {
    Path jar = compileToJar(
        EMPTY_CLASSPATH,
        "A.java",
        Joiner.on("\n").join(ImmutableList.of(
                "package com.example.buck;",
                "public class A {",
                "  public String toString() { return null; }",
                "  public void eatCake() {}",
                "}")));

    Path classDir = temp.newFolder().toPath();
    Unzip.extractZipFile(jar, classDir, true);

    new StubJar(classDir).writeTo(stubJar);

    // Verify that both methods are present and given in alphabetical order.
    ClassNode classNode = readClass(stubJar, "com/example/buck/A.class");
    List<MethodNode> methods = classNode.methods;
    // Index 0 is the <init> method. Skip that.
    assertEquals("eatCake", methods.get(1).name);
    assertEquals("toString", methods.get(2).name);
  }

  private ClassNode readClass(Path pathToJar, String className) throws IOException {
    try (ZipFile zip = new ZipFile(pathToJar.toFile())) {
      ZipEntry entry = zip.getEntry(className);
      try (InputStream entryStream = zip.getInputStream(entry)) {
        ClassReader reader = new ClassReader(entryStream);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        return classNode;
      }
    }
  }

  private MethodNode findMethod(ClassNode clazz, String methodName) {
    for (MethodNode method : clazz.methods) {
      if (method.name.equals(methodName)) {
        return method;
      }
    }
    fail("Unable to find method with name: " + methodName);
    return null;
  }

  private FieldNode findField(ClassNode clazz, String fieldName) {
    for (FieldNode field : clazz.fields) {
      if (field.name.equals(fieldName)) {
        return field;
      }
    }

    fail("Unable to find field with name: " + fieldName);
    return null;
  }

  private Path buildAnnotationJar() throws IOException {
    return compileToJar(
        EMPTY_CLASSPATH,
        "Foo.java",
        Joiner.on("\n").join(ImmutableList.of(
            "package com.example.buck;",
            "import java.lang.annotation.*;",
            "import static java.lang.annotation.ElementType.*;",
            "@Retention(RetentionPolicy.RUNTIME)",
            "@Target(value={CONSTRUCTOR, FIELD, METHOD, PARAMETER, TYPE})",
            "public @interface Foo {}"
            )));
  }

  private void assertMethodEquals(MethodNode expected, MethodNode seen) {
    assertEquals(expected.access, seen.access);
    assertEquals(expected.desc, seen.desc);
    assertEquals(expected.signature, seen.signature);
  }

  private void assertFieldEquals(FieldNode expected, FieldNode seen) {
    assertEquals(expected.name, seen.name);
    assertEquals(expected.desc, seen.desc);
    assertEquals(expected.signature, seen.signature);
  }
}
