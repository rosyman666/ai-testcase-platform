package com.aittest.export.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * JUnit 生成物的**验收期**真编译支持（docs/07 §1.5 方案 A / §8 T02 完成判据 ⑨）。
 *
 * <p><b>本类只在测试里调用，生产导出链路不调用</b>。它要挡住的是「只断言文件生成」这种
 * 无对象假绿：0 个编译单元时 {@code getDiagnostics()} 天然没有 ERROR，绿灯毫无意义。</p>
 *
 * <p><b>classpath 只含 JDK</b>：不解析 {@code ~/.m2}、不拼 {@code junit-jupiter-api} /
 * {@code apiguardian-api} / {@code opentest4j} / {@code junit-platform-commons} 四个 jar。
 * 注解定义随 zip 一起生成（{@code org/junit/jupiter/api/*.java}），编译期零外部依赖，
 * 因此 fat-jar 运行时也能复现。显式设置 {@code -classpath} 还能把测试 JVM 自身的
 * junit jar 挡在外面，避免「注解桩其实没被用到、靠真实 JUnit 编译通过」的假绿。</p>
 *
 * @author 寇豆码
 */
public final class JUnitCompileSupport {

    /** 编译产物目录名（位于解压根下）。 */
    public static final String CLASSES_DIR_NAME = "classes";

    private JUnitCompileSupport() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 方案 A 的 classpath：空字符串表示「不引入任何外部 jar」。
     *
     * @return 恒为空串
     */
    public static String resolveClasspath() {
        return "";
    }

    /**
     * 编译产物目录。
     *
     * @param root 解压根目录
     * @return {@code root/classes}
     */
    public static Path classesDir(Path root) {
        return root.resolve(CLASSES_DIR_NAME);
    }

    /**
     * 递归枚举解压根下的全部 {@code .java}（用例类 + 注解桩 + runner），排除编译产物目录。
     *
     * @param root 解压根目录
     * @return 按路径字典序排列的编译单元
     * @throws IOException 目录不可读
     */
    public static List<Path> listJavaUnits(Path root) throws IOException {
        Path excluded = classesDir(root);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(excluded))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * 用 JDK 17 {@link JavaCompiler} 编译解压根下全部 {@code .java}。
     *
     * <p>每次调用先清空 {@code root/classes}，让「负例重编译」不会看到上一次的 .class 残留。
     * 编译是否成功由调用方按 {@code Diagnostic.Kind.ERROR} 数量判断，本方法不吞掉诊断。</p>
     *
     * @param root 解压根目录
     * @return 全部诊断（含 WARNING / NOTE），不为空列表
     * @throws IOException 目录读写失败
     * @throws IllegalStateException 运行环境不是 JDK（无系统编译器）
     */
    public static List<Diagnostic<? extends JavaFileObject>> compileAll(Path root) throws IOException {
        Path classes = classesDir(root);
        if (Files.exists(classes)) {
            deleteTree(classes);
        }
        Files.createDirectories(classes);

        List<Path> units = listJavaUnits(root);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("当前运行环境无 javax.tools.JavaCompiler，请使用 JDK 17 而非 JRE");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjectsFromPaths(units);
            List<String> options = new ArrayList<>(List.of(
                    "-encoding", "UTF-8",
                    "-source", "17",
                    "-target", "17",
                    "-proc:none",
                    "-sourcepath", root.toString(),
                    "-classpath", classes.toString(),
                    "-d", classes.toString()));
            compiler.getTask(null, fileManager, collector, options, null, sources).call();
        }
        return List.copyOf(collector.getDiagnostics());
    }

    /**
     * 统计诊断里的 ERROR 条数。
     *
     * @param diagnostics 编译诊断
     * @return ERROR 数（0 表示编译通过）
     */
    public static long countErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .count();
    }

    /**
     * 统计编译产物目录下 {@code .class} 文件数。
     *
     * <p>用于证明「真的编译了」而不是空跑：每个顶层类型至少产出 1 个 class。</p>
     *
     * @param root 解压根目录
     * @return .class 数
     * @throws IOException 目录不可读
     */
    public static long countClassFiles(Path root) throws IOException {
        Path classes = classesDir(root);
        if (!Files.isDirectory(classes)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(classes)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .count();
        }
    }

    /**
     * 递归删除目录（编译前清理产物）。
     *
     * @param dir 待删目录
     * @throws IOException 删除失败
     */
    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            List<Path> paths = stream
                    .sorted(Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
