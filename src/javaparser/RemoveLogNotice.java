package javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.utils.SourceRoot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * References:
 * http://javaparser.org/
 * https://www.javaadvent.com/2017/12/javaparser-generate-analyze-modify-java-code.html
 * <p>
 * This class implemented a method to replace some method like:
 * LogNotice.info("aaa", "bb", "cc", [e])
 * -> to
 * LogMsg.info("aa, bb", "cc", [e])
 * <p>
 * <p>
 * Note: this will overwrite the files.
 * Created by edward.gao on 10/26/16.
 */
public class RemoveLogNotice {
    private static Stack<CompilationUnit> allCus = new Stack<>();

    private static String STARTPACKAGE = ""; //  the package name like "aaa.bbc.ccc"
    private static String SOURCE_FOLDER = ""; // the base source folder like "/code/java/src"

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Need at least two args:  codeSourceFolder startPackageName");
            return;
        }
        SOURCE_FOLDER = args[0];
        STARTPACKAGE = args[1];
        init(SOURCE_FOLDER, STARTPACKAGE);
        while (!allCus.isEmpty()) {
            CompilationUnit u = allCus.pop();
            if (processOneFile(u)) {
                allCus.push(reCompile(u.getStorage().get().getPath()));
            }
        }

    }

    /**
     * Process a single compilation unit, that's a file too
     *
     * @param u
     * @return return whether we need to re-run for all files. because when a file changed, the lines and related information will be
     * changed.
     * return true when we want to re-run for all files.
     * @throws IOException
     */
    public static boolean processOneFile(CompilationUnit u) throws IOException {
        System.out.println("File " + u.getStorage().get().getFileName());
        Stack<Node> processStack = new Stack<>();

        // addd all constructor methods and normal methods to the process stack, we will process it later
        u.findAll(ConstructorDeclaration.class)
                .stream().map(m -> m.getBody())
                .forEach(m -> processStack.push(m));
        u.findAll(MethodDeclaration.class)
                .stream().filter(m -> m.getBody().isPresent()).map(m -> m.getBody().get())
                .forEach(m -> processStack.push(m));


        while (!processStack.isEmpty()) {

            Node curSt = processStack.pop();
            if (curSt instanceof ExpressionStmt) {
                Expression expr = ((ExpressionStmt) curSt).getExpression();
                if (expr instanceof MethodCallExpr) {
                    MethodCallExpr expression = (MethodCallExpr) expr;
                    if (expression.getScope().isPresent() && expression.getScope().get().toString().equals("LogNotice")) {

                        /**
                         * here we start to replace the methods....
                         * it's may be complex, but the main logic is:
                         * if some arg is a StringLiteralExpr we can
                         */
                        NodeList<Expression> arguments = expression.getArguments();
                        if (arguments.size() >= 3 && arguments.size() <= 4) {
                            Expression first = arguments.get(0);
                            Expression second = arguments.get(1);
                            if (isValidExpr(first) && isValidExpr(second)) {
                                String reComposedMethod = reComposeMethod(expression);

                                Range lineRange = expression.getRange().get();
                                // expression = JavaParser.parseExpression(newString.toString());
                                if (!expression.toString().endsWith(";")) {
                                    // if the raw expression didn't contains the ;
                                    // the replace string should not contain ; too
                                    reComposedMethod = reComposedMethod.substring(0, reComposedMethod.length() - 1);
                                }

                                Helper.reWriteCode(u.getStorage().get().getPath(), expression.toString(), reComposedMethod, lineRange);
                                System.out.println("The reformat string is " + reComposedMethod);

                                return true;
                            }
                            else {
                                if (first instanceof ObjectCreationExpr) {
                                    NodeList<BodyDeclaration<?>> nodeList = first.asObjectCreationExpr()
                                            .getAnonymousClassBody().get();
                                    nodeList.stream().forEach(n -> processStack.push(n));
                                }
                                else {
                                    throw new IllegalArgumentException("Not support this yet " + expression);
                                }
                            }

                        }
                        else {
                            throw new IllegalArgumentException("The arguments is not 3 or 4 args - " + expression);
                        }

                    }
                }
                else {
                    if (!curSt.getChildNodes().isEmpty()) {
                        for (Node n : curSt.getChildNodes()) {
                            if (n instanceof Node) {
                                processStack.push(n);
                            }
                        }
                    }
                }
            }
            else {
                curSt.getChildNodes().stream().forEach(n -> processStack.push(n));
            }
        }
        return false;
    }


    /**
     * re-compose of the LogMsg from LogNotice method.
     *
     * @param methodCallExpr
     * @return the re-write method string
     */
    static String reComposeMethod(MethodCallExpr methodCallExpr) {
        NodeList<Expression> arguments = methodCallExpr.getArguments();
        String methodName = methodCallExpr.getNameAsString(); // info

        StringBuilder newString = new StringBuilder("LogMsg." + methodName + "(");

        Expression first = arguments.get(0);
        Expression second = arguments.get(1);

        if (first instanceof StringLiteralExpr) {
            if (second instanceof StringLiteralExpr) {
                //
                newString.append("\"").append(first.asStringLiteralExpr().asString() + ", " +
                        second.asStringLiteralExpr().asString()).append("\"");
            }
            else {
                //
                newString.append("\"").append(first.asStringLiteralExpr().asString()).append("\" " +
                        "+ ").append(second.toString());
            }
        }
        else {
            if (second instanceof StringLiteralExpr) {
                //
                newString.append(first.toString()).append(" + ").append(second
                        .asStringLiteralExpr().toString());
            }
            else {
                //
                newString.append(first.toString()).append(" + ").append(second.toString());
            }
        }
        newString.append(", ");
        newString.append(arguments.get(2).toString());
        if (arguments.size() == 4) {
            newString.append(", ").append(arguments.get(3).toString());
        }
        newString.append(");");

        return newString.toString();


    }


    /**
     * whether the cu contains the LogNotice class import
     *
     * @param u
     * @return
     */
    private static boolean containsOurMethod(CompilationUnit u) {
        for (ImportDeclaration id : u.getImports()) {
            if (id.getName().asString().equals("com.santaba.common.logger.LogNotice")
                    ) {
                return true;
            }
        }
        if (u.toString().contains("LogNotice")) {
            return true;
        }
        System.out.println("Skipped " + u.getStorage().get().getFileName());
        return false;
    }

    private static boolean isValidExpr(Expression e) {
        return true;
    }


    private static CompilationUnit reCompile(Path file) throws IOException {
        try {
            return JavaParser.parse(file);
        } catch (Exception e) {
            System.err.println("Fail to parse file " + file);
            throw e;
        }
    }

    /**
     * init the cu sets.
     *
     * @param srcFolder
     * @param startPackage
     * @throws IOException
     */
    private static void init(String srcFolder, String startPackage) throws IOException {
        allCus.clear();
        ParserConfiguration conf = new ParserConfiguration();
        conf.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
        // Parse all source files
        SourceRoot sourceRoot = new SourceRoot(new File(srcFolder).toPath());
        sourceRoot.setParserConfiguration(conf);
        List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse(startPackage);

        allCus.addAll(parseResults.stream()
                .filter(ParseResult::isSuccessful)
                .filter(pr -> containsOurMethod(pr.getResult().get()))
                .map(r -> r.getResult().get())
                .collect(Collectors.toList())
        );
    }

}
