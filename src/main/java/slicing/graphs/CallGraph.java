package slicing.graphs;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import lombok.var;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedPseudograph;
import slicing.graphs.cfg.CFG;
import slicing.nodes.GraphNode;
import slicing.utils.ASTUtils;
import slicing.utils.NodeHashSet;
import slicing.utils.NodeNotFoundException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A directed graph which displays the available method declarations as nodes and their
 * invocations as edges (caller to callee).
 * <br/>
 * Method declarations include both {@link ConstructorDeclaration constructors}
 * and {@link MethodDeclaration method declarations}.
 * In the future, {@link InitializerDeclaration static initializer blocks} and field initializers will be included.
 * <br/>
 * Method calls include only direct method calls, from {@link MethodCallExpr normal call},
 * to {@link ObjectCreationExpr object creation} and {@link ExplicitConstructorInvocationStmt
 * explicit constructor invokation} ({@code this()}, {@code super()}).
 */
public class CallGraph extends DirectedPseudograph<CallGraph.Vertex, CallGraph.Edge<?>> implements Buildable<NodeList<CompilationUnit>> {
    private final Map<CallableDeclaration<?>, CFG> cfgMap;
    private final ClassGraph classGraph;

    private boolean built = false;

    public CallGraph(Map<CallableDeclaration<?>, CFG> cfgMap, ClassGraph classGraph) {
        super(null, null, false);
        this.cfgMap = cfgMap;
        this.classGraph = classGraph;
    }

    /** Resolve a call to all its possible declarations, by using the call AST nodes stored on the edges. */
    public Stream<CallableDeclaration<?>> getCallTargets(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        return edgeSet().stream()
                .filter(e -> ASTUtils.equalsWithRange(e.getCall(), call))
                .map(this::getEdgeTarget)
                .map(Vertex::getDeclaration)
                .map(decl -> (CallableDeclaration<?>) decl);
    }

    /** Locates the calls to a given declaration. The result is any node that represents a call. */
    public Stream<Node> callsTo(CallableDeclaration<?> callee) {
        return incomingEdgesOf(findVertexByDeclaration(callee)).stream()
                .map(Edge::getCall)
                .map(Node.class::cast);
    }

    /** Locates the calls contained in a given method or constructor.
     *  See {@link #callsTo(CallableDeclaration)} for the return value. */
    public Stream<Node> callsFrom(CallableDeclaration<?> caller) {
        return outgoingEdgesOf(findVertexByDeclaration(caller)).stream()
                .map(Edge::getCall)
                .map(Node.class::cast);
    }

    /** Locates the methods that may call the given declaration. */
    public Stream<CallableDeclaration<?>> callersOf(CallableDeclaration<?> callee) {
        return incomingEdgesOf(findVertexByDeclaration(callee)).stream()
                .map(this::getEdgeSource)
                .map(Vertex::getDeclaration);
    }

    /** Locates the methods that may be called when executing the given declaration. */
    public Stream<CallableDeclaration<?>> calleesOf(CallableDeclaration<?> caller) {
        return outgoingEdgesOf(findVertexByDeclaration(caller)).stream()
                .map(this::getEdgeTarget)
                .map(Vertex::getDeclaration);
    }

    /** Locate the vertex that represents in this graph the given declaration. */
    protected Vertex findVertexByDeclaration(CallableDeclaration<?> declaration) {
        Optional<Vertex> matchingVertex = vertexSet().stream()
                .filter(v -> v.matches(declaration))
                .findFirst();

        if (matchingVertex.isPresent()) {
            return matchingVertex.get();
        } else {
            throw new NoSuchElementException("No vertex matching the declaration found");
        }

    }

    @Override
    public void build(NodeList<CompilationUnit> arg) {
        if (isBuilt())
            return;
        buildVertices(arg);
        buildEdges(arg);
        built = true;
    }

    @Override
    public boolean isBuilt() {
        return built;
    }

    /** Find the method and constructor declarations (vertices) in the given list of compilation units. */
    protected void buildVertices(NodeList<CompilationUnit> arg) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                addVertex(new Vertex(n));
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                addVertex(new Vertex(n));
                super.visit(n, arg);
            }
        }, null);
    }

    protected boolean addEdge(CallableDeclaration<?> source, CallableDeclaration<?> target, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        try {
            Edge<?> edge = new Edge<>(call, findGraphNode(call, source));
            return addEdge(findVertexByDeclaration(source), findVertexByDeclaration(target), edge);
        } catch (Exception e) {
            // Failed to create edge, just ignore
            return false;
        }
    }

    protected boolean addEdge(TypeDeclaration<?> source, CallableDeclaration<?> target, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
        return false; // TODO: handle static blocks
    }

    /** Find the calls to methods and constructors (edges) in the given list of compilation units. */
    protected void buildEdges(NodeList<CompilationUnit> arg) {
        arg.accept(new VoidVisitorAdapter<Void>() {
            private final Deque<TypeDeclaration<?>> typeStack = new LinkedList<>();
            private final Deque<CallableDeclaration<?>> declStack = new LinkedList<>();

            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                typeStack.push(n);
                super.visit(n, arg);
                typeStack.pop();
            }

            // ============ Method declarations ===========
            // There are some locations not considered, which may lead to an error in the stack.
            // 1. Method calls in non-static field initializations are assigned to all constructors of that class
            // 2. Method calls in static field initializations are assigned to the static block of that class

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                declStack.push(n);
                super.visit(n, arg);
                declStack.pop();
            }

            // =============== Method calls ===============
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                try {
                    if (!n.resolve().toAst().isPresent()) {
                        CallableDeclaration<?> decl = classGraph.getMethodDeclarationBySig(ASTUtils.processSignature(n.resolve().getQualifiedSignature()));
                        if (decl != null) {
                            createPolyEdges(decl.asMethodDeclaration(), n);
                        }
                    } else {
                        n.resolve().toAst().ifPresent(decl -> createPolyEdges(decl, n));
                    }
                } catch (RuntimeException ignored) {}
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                try {
                    if (!n.resolve().toAst().isPresent()) {
                        CallableDeclaration<?> decl = classGraph.getMethodDeclarationBySig(ASTUtils.processSignature(n.resolve().getQualifiedSignature()));
                        if (decl != null) {
                            createNormalEdge(decl, n);
                        }
                    } else {
                        n.resolve().toAst().ifPresent(decl -> createNormalEdge(decl, n));
                    }
                } catch (RuntimeException ignored) {}
                super.visit(n, arg);
            }

            @Override
            public void visit(ExplicitConstructorInvocationStmt n, Void arg) {
                try {
                    n.resolve().toAst().ifPresent(decl -> createNormalEdge(decl, n));
                } catch (RuntimeException ignored) {}
                super.visit(n, arg);
            }

            protected void createPolyEdges(MethodDeclaration decl, MethodCallExpr call) {
                // Static calls have no polymorphism, ignore
                if (decl.isStatic()) {
                    createNormalEdge(decl, call);
                    return;
                }
                try {
                    Optional<Expression> scope = call.getScope();
                    // Determine the type of the call's scope
                    Set<? extends TypeDeclaration<?>> dynamicTypes;
                    if (!scope.isPresent()) {
                        // a) No scope: any class the method is in, or any outer class if the class is not static.
                        // Early exit: it is easier to find the methods that override the
                        // detected call than to account for all cases (implicit inner or outer class)
                        classGraph.overriddenSetOf(decl)
                                .forEach(methodDecl -> createNormalEdge(methodDecl, call));
                        return;
                    } else if (scope.get().isThisExpr() && !scope.get().asThisExpr().getTypeName().isPresent()) {
                        // b) just 'this', the current class and any subclass
                        dynamicTypes = classGraph.subclassesOf(typeStack.peek());
                    } else if (scope.get().isThisExpr()) {
                        // c) 'ClassName.this', the given class and any subclass
                        dynamicTypes = classGraph.subclassesOf(scope.get().asThisExpr().resolve().asClass());
                    } else if (scope.get().isSuperExpr()) {
                        // d) 'super': start with the parent type and get the first implementation
                        dynamicTypes = new HashSet<>(Collections.singleton(classGraph.parentOf(typeStack.peek()).orElseThrow(() -> new NoSuchElementException("No parent found for the type"))));
                    } else {
                        // e) others: compute possible dynamic types of the expression (TODO)
                        dynamicTypes = classGraph.subclassesOf(scope.get().calculateResolvedType().asReferenceType());
                    }
                    // Locate the corresponding methods for each possible dynamic type, they must be available to all
                    // To locate them, use the method signature and search for it in the class graph
                    // Connect to each declaration
                    AtomicInteger edgesCreated = new AtomicInteger();
                    dynamicTypes.stream()
                            .map(t -> classGraph.findMethodByTypeAndSignature(t, decl))
                            .collect(Collectors.toCollection(NodeHashSet::new))
                            .forEach(methodDecl -> {
                                edgesCreated.getAndIncrement();
                                createNormalEdge(methodDecl, call);
                            });
                    assert edgesCreated.get() > 0;
                } catch (Exception e) {
                    // Failed to create edge, just ignore
                    return;
                }
            }

            protected void createNormalEdge(CallableDeclaration<?> decl, Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
                if (declStack.isEmpty() && typeStack.isEmpty())
                    throw new IllegalStateException("Trying to link call with empty declaration stack! " + decl.getDeclarationAsString() + " : " + call.toString());
                if (declStack.isEmpty())
                    addEdge(typeStack.peek(), decl, call);
                else
                    addEdge(declStack.peek(), decl, call);
            }

            // Other structures
            @Override
            public void visit(FieldDeclaration n, Void arg) {
                if (declStack.isEmpty() && !n.isStatic()) {
                    for (ConstructorDeclaration cd : typeStack.peek().getConstructors()) {
                        declStack.push(cd);
                        super.visit(n, arg);
                        declStack.pop();
                    }
                }
            }
        }, null);
    }

    /** Locates the node in the collection of CFGs that contains the given call. */
    protected GraphNode<?> findGraphNode(Resolvable<? extends ResolvedMethodLikeDeclaration> n, CallableDeclaration<?> declaration) {
        for (GraphNode<?> node : cfgMap.get(declaration).vertexSet())
            if (node.containsCall(n))
                return node;
        throw new NodeNotFoundException("call " + n + " could not be located! cfg was " + cfgMap.get(declaration).rootNode.getLongLabel() + " and declaration was " + declaration.getDeclarationAsString());
    }

    /** A vertex containing the declaration it represents. It only exists because
     *  JGraphT relies heavily on equals comparison, which may not be correct in declarations. */
    public static class Vertex {
        protected final CallableDeclaration<?> declaration;

        public Vertex(CallableDeclaration<?> declaration) {
            assert declaration instanceof ConstructorDeclaration || declaration instanceof MethodDeclaration;
            this.declaration = declaration;
        }

        /** The declaration represented by this node. */
        public CallableDeclaration<?> getDeclaration() {
            return declaration;
        }

        @Override
        public int hashCode() {
            return Objects.hash(declaration.getSignature());
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Vertex && ((Vertex) obj).matches(declaration);
        }

        @Override
        public String toString() {
            return declaration.toString();
        }

        public boolean matches(CallableDeclaration<?> declaration) {
            if (this.declaration == declaration)
                return true;
            if (!this.declaration.getSignature().toString().equals(declaration.getSignature().toString()))
                return false;
            NodeWithSimpleName t1 = this.declaration.findAncestor(NodeWithSimpleName.class).orElse(null);
            NodeWithSimpleName t2 = declaration.findAncestor(NodeWithSimpleName.class).orElse(null);
            return t1 != null && t2 != null && t1.getNameAsString().equals(t2.getNameAsString());
        }
    }

    /** An edge containing the call it represents, and the graph node that contains it. */
    public static class Edge<T extends Resolvable<? extends ResolvedMethodLikeDeclaration>> extends DefaultEdge {
        protected final T call;
        protected final GraphNode<?> graphNode;

        public Edge(T call, GraphNode<?> graphNode) {
            assert call instanceof MethodCallExpr || call instanceof ObjectCreationExpr || call instanceof ExplicitConstructorInvocationStmt;
            assert graphNode.containsCall(call);
            this.call = call;
            this.graphNode = graphNode;
        }

        /** The call represented by this edge. Using the {@link CallGraph} it can be effortlessly resolved. */
        public T getCall() {
            return call;
        }

        /** The graph node that contains the call represented by this edge. */
        public GraphNode<?> getGraphNode() {
            return graphNode;
        }

        public CallableDeclaration<?> getSource() {
            return ((Vertex) super.getSource()).getDeclaration();
        }

        public CallableDeclaration<?> getTarget() {
            return ((Vertex) super.getTarget()).getDeclaration();
        }

        @Override
        public String toString() {
            return String.format("%s -%d-> %s",
                    ((CallableDeclaration<?>) getSource()).getDeclarationAsString(false, false, false),
                    graphNode.getId(),
                    ((CallableDeclaration<?>) getTarget()).getDeclarationAsString(false, false, false));
        }
    }
}
