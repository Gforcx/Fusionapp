import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax only. This explicitly does not resolve Android APIs or build an APK. */
public class ParseJava {
    public static void main(String[] args) throws Exception {
        List<String> sources=new ArrayList<>();
        try(var paths=Files.walk(Path.of(args[0]))){paths.filter(p->p.toString().endsWith(".java")).forEach(p->sources.add(p.toString()));}
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--release","17"),null,manager.getJavaFileObjectsFromStrings(sources));
            task.parse();
            for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
        }
        System.out.println("PASS: syntax parsing of "+sources.size()+" Android Java source files (not an Android compilation)");
    }
}
