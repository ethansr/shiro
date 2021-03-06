package shiro;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.collections15.map.HashedMap;
import shiro.dag.DAGraph;
import shiro.dag.DependencyRelation;
import shiro.dag.GraphNode;
import shiro.dag.TopologicalSort;
import shiro.events.NodeEvent;
import shiro.events.NodeEventListener;
import shiro.events.SubjParametricSystemEvent;
import shiro.events.SubjParametricSystemEventListener;
import shiro.expressions.Path;
import shiro.functions.MultiFunction;
import shiro.functions.MultiplyMFunc;
import shiro.functions.SumMFunc;
import shiro.functions.ValueMFunc;
import shiro.functions.graphics.CanvasMFunc;
import shiro.functions.graphics.LineMFunc;
import shiro.functions.graphics.PointMFunc;
import shiro.interpreter.EvaluateAlternativeListener;
import shiro.interpreter.NodeProductionListener;

/**
 * A subjunctive parametric system.
 *
 * @author jeffreyguenther
 */
public class SubjunctiveParametricSystem implements NodeEventListener, Scope {

    private Map<String, MultiFunction> multiFunctions; // multifunction symbol table
    private DAGraph<Port> depGraph;                    // realized dependency graph
    private Map<String, ParseTree> nodeDefs;           // AST table
    private Map<String, ParseTree> subjNodeDefs;       // AST table
    private Map<String, ParseTree> alternativeDefs;    // AST table
    private Map<String, Node> nodes;                   // realized node table
    private Map<String, SubjunctiveNode> subjNodes;    // realized subj. node table
    private Map<String, SystemState> alternatives;     // alternative specs
    private PortAction graphNodeAction;                // action used in graph nodes
    
    private Set<SubjParametricSystemEventListener> listeners; // Event listeners

    public SubjunctiveParametricSystem() {
        multiFunctions = new HashMap<String, MultiFunction>();
        // load the multifunction map
        loadMultiFunctions(multiFunctions);

        depGraph = new DAGraph<Port>();
        nodeDefs = new HashMap<String, ParseTree>();
        subjNodeDefs = new HashMap<String, ParseTree>();
        alternativeDefs = new HashMap<String, ParseTree>();
        nodes = new HashedMap<String, Node>();
        subjNodes = new HashMap<String, SubjunctiveNode>();
        alternatives = new HashMap<String, SystemState>();
        graphNodeAction = new PortAction();

        listeners = new HashSet<SubjParametricSystemEventListener>();
    }

    /**
     * *
     * Creates a single instance of all of the multi-functions the system knows
     * about. This creates the symbol table for looking up multi-function
     * objects This method will need to be changed if we load multi-functions at
     * runtime.
     *
     * @param funcMap
     */
    private void loadMultiFunctions(Map<String, MultiFunction> funcMap) {
        funcMap.put("Value", new ValueMFunc());
        funcMap.put("Multiply", new MultiplyMFunc());
        funcMap.put("Sum", new SumMFunc());

        // add graphics MFs
        funcMap.put("Point", new PointMFunc());
        funcMap.put("Line", new LineMFunc());
        funcMap.put("Canvas", new CanvasMFunc());
    }

    /**
     * Store a multi-function in the symbol table.
     *
     * @param name of multi-function
     * @param mf multi-function to be added
     */
    public void loadMultiFunction(String name, MultiFunction mf) {
        multiFunctions.put(name, mf);
    }

    /**
     * **
     * Looks up a multi-function object by name
     *
     * @param name of the multi-function
     * @return a multi-function
     */
    public MultiFunction getFunction(String name) {
        return multiFunctions.get(name);
    }

    /**
     * Resolve a symbol from a given path.
     *
     * @param p path to the resolved
     * @return a node or port that the path corresponds to.
     * @throws PathNotFoundException
     */
    @Override
    public Symbol resolvePath(Path p) throws PathNotFoundException {
        Symbol matchedPort = null;

        // check if any realized node names match the start of the path
        Node matchedNode = nodes.get(p.getCurrentPathHead());
        SubjunctiveNode matchedSNode = subjNodes.get(p.getCurrentPathHead());
        if (matchedNode != null) {
            // pop the path head
            p.popPathHead();
            // attempt to find the path in the node
            matchedPort = matchedNode.resolvePath(p);
        } else if (matchedSNode != null) {
            // pop the head of the path
            p.popPathHead();

            matchedPort = matchedSNode.resolvePath(p);
        } else if (nodeDefs.get(p.getCurrentPathHead()) != null) {
            // determine if desired path is a node not yet realized
            // create the new
            matchedNode = produceNodeFromName(p.getCurrentPathHead(), p.getCurrentPathHead());
            // attempt to find the port in the realized node
            // pop the path head
            p.popPathHead();
            // attempt to find the path in the node
            matchedPort = matchedNode.resolvePath(p);
        }

        // IF after checking both the realized and unrealized nodes
        if (matchedPort == null) {
            //  ERROR
            throw new PathNotFoundException(p + " cannnot be resolved.");
        }

        return matchedPort;
    }

    public Symbol produceSymbolFromName(String name, String newname) {
        // check to see if the name is a node
        if (nodeDefs.containsKey(name)) {
            return produceNodeFromName(name, newname);
            // check if name is subjunctive node
        } else if (subjNodeDefs.containsKey(name)) {
            return produceSubjNodeFromName(name, newname);
        }

        return null;
    }

    public SubjunctiveNode produceSubjNodeFromName(String name, String newName) {
        SubjunctiveNode producedNode = createSubjNode(name);
        producedNode.setName(newName);
        addSubjunctiveNode(producedNode);
        return producedNode;
    }

    /**
     * *
     * Duplicate a node and change its name.
     *
     * @param name of node to duplicate
     * @param newName of node
     * @return new node produced using path and newName.
     */
    public Node produceNodeFromName(String name, String newName) {

        Node producedNode = createNode(name);

        // change the node's name
        producedNode.setFullName(newName);
        addNode(producedNode);

        //update the full name of all of the ports in the node
        for (Port p : producedNode.getPorts()) {
            String newPath = producedNode.getFullName() + "." + p.getName();
            p.setFullName(newPath);
            // remove the null listeners created during duplication process
            p.clearListeners();
            p.addPortEventListener(producedNode);
        }

        // set the enclosing scope of the new node to the current SPS reference
        producedNode.setParentScope(this);

        return producedNode;
    }

    public void addSubjunctiveNode(SubjunctiveNode n) {
        subjNodes.put(n.getName(), n);
    }

    /**
     * Add a node to the system
     *
     * @param n
     */
    public void addNode(Node n) {
        nodes.put(n.getName(), n);
    }

    /**
     * Get a reference to a node by name
     *
     * @param name of node to be returned
     * @return a reference to the node of the given name.
     */
    public Node getNode(String name) {
        return nodes.get(name);
    }

    /**
     * Get a node for the path
     *
     * @param p path to lookup
     * @return node referenced by path
     */
    public Node getNode(Path p) {
        // check the realized nodes
        Node match = nodes.get(p.getCurrentPathHead());

        // check to see if path refers to an unrealized node
        if (match == null //&& nodeASTDefinitions.containsKey(p.getCurrentPathHead())
                ) {
            // If it does, build the node.
            match = createNode(p.getCurrentPathHead());

            // add to collection of realized nodes.
            nodes.put(match.getName(), match);
        }

        if (match != null) {
            p.popPathHead();


            // check if the path has any more
            if (match.hasNestedContainers() && !p.isEmpty() && !p.isPathToPortIndex()) {

                for (Container nested : match.getNestedContainers()) {
                    Node nestedNode = (Node) nested;
                    Node match2 = nestedNode.getNode(p);
                    if (match2 != null) {
                        return match2.getNode(p);
                    }
                }
            }

            // RESET the path
            p.resetPathHead();
        }
        return match;
    }

    /**
     * Get all of the nodes in the system.
     *
     * @return a set of all of the nodes in the system
     */
    public Set<Node> getNodes() {
        return new HashSet<Node>(nodes.values());
    }

    /**
     * Remove a node
     *
     * @param name of node to be removed
     */
    public void removeNode(String name) {
        removeNode(nodes.get(name));
    }

    /**
     * Remove a node
     *
     * @param node reference to the node to be removed
     */
    public void removeNode(Node node) {
        // remove all of the node's ports from the graph
        for (Port p : node.getPorts()) {
            //depGraph.removePort(p.getFullName());
        }

        // take care of removing the node if it is in aa subjunct
        for (SubjunctiveNode sn : subjNodes.values()) {
            if (sn.hasSubjunct(node)) {
                try {
                    sn.removeSubjunct(node);
                } catch (SubjunctNotFoundException ex) {
                    Logger.getLogger(SubjunctiveParametricSystem.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }

        // remove the node
        nodes.remove(node.getName());
    }

    /**
     * Add a port to the dependency graph
     *
     * @param p port to be added
     */
    public void addPort(Port p) {
        depGraph.addNode(new GraphNode<Port>(p, graphNodeAction));
    }

    /**
     * Add a dependency between two ports
     *
     * @param dependency dependency relation to be added to the graph
     */
    public void addDependency(DependencyRelation<Port> dependency) {
        addDependency(dependency.getDependent(), dependency.getDependedOn());
    }

    /**
     * *
     * Add a dependency between two ports A - depends on -> B
     *
     * @param a the depended on port reference
     * @param b the dependent port reference
     */
    public void addDependency(Port a, Port b) {
        if (b == null) {
            depGraph.addDependency(new GraphNode<Port>(a, graphNodeAction), null);
        } else {
            depGraph.addDependency(depGraph.getNodeForValue(a, graphNodeAction),
                    depGraph.getNodeForValue(b, graphNodeAction));
        }
    }

    /**
     * Set the active node in a subjunctive node
     *
     * @param sNode the subjunctive node
     * @param activeNode the node to be set as active subjunct
     * @throws SubjunctNotFoundException
     */
    public void setActiveNode(SubjunctiveNode sNode, Node activeNode) throws SubjunctNotFoundException {
        try {
            sNode.activate(activeNode.getPath());
        } catch (PathNotFoundException ex) {
            throw new SubjunctNotFoundException(ex.getMessage());
        }
    }

    /**
     * Add the ASTs for each of the nodes. These trees are used to generate node
     * instances when the graph is being built.
     *
     * @param map map of node name to AST trees
     * @return the current state of the definitions map
     */
    public Map<String, ParseTree> addNodeASTDefinitions(Map<String, ParseTree> map) {
        nodeDefs.putAll(map);
        return nodeDefs;
    }

    public Map<String, ParseTree> addSubjNodeASTDefinitions(Map<String, ParseTree> map) {
        subjNodeDefs.putAll(map);
        return subjNodeDefs;
    }

    public Map<String, ParseTree> addAlternativeASTDefinitions(Map<String, ParseTree> map) {
        alternativeDefs.putAll(map);
        return alternativeDefs;
    }
    
    public String printDependencyGraph() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes.values()) {
            sb.append(n);
            sb.append("\n");
        }

        for (SubjunctiveNode n : subjNodes.values()) {
            sb.append(n);
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Update the parametric system
     */
    public void update() {


        ParseTreeWalker walker = new ParseTreeWalker();
        EvaluateAlternativeListener genAlts = new EvaluateAlternativeListener(this);

        for (ParseTree altTree : alternativeDefs.values()) {
            walker.walk(genAlts, altTree);
        }

        for (SystemState a : alternatives.values()) {
            Map<SubjunctiveNode, Node> subjunctTable = a.getSubjunctsMapping();

            for (SubjunctiveNode s : subjunctTable.keySet()) {
                try {
                    s.activate(subjunctTable.get(s).getName());
                } catch (PathNotFoundException ex) {
                    Logger.getLogger(SubjunctiveParametricSystem.class.getName()).log(Level.SEVERE, null, ex);
                }
            }

            List<DependencyRelation<Port>> deps = new ArrayList<DependencyRelation<Port>>();

            // for each node generated in the graph generation process
            for (Node n : getNodes()) {
                // get all of the dependencies for each node
                deps.addAll(n.getDependencies());
            }

            System.out.println();
            System.out.println("Dependencies");
            for (DependencyRelation<Port> d : deps) {
                System.out.println(d.getDependent().getFullName() + " -> " + d.getDependedOn().getFullName());
                addDependency(d);
            }
            
            TopologicalSort<Port> sorter = new TopologicalSort<Port>(depGraph);
            List<GraphNode<Port>> topologicalOrdering = sorter.getTopologicalOrdering();

            // loop through all ports to update them.
            for (GraphNode<Port> gn : topologicalOrdering) {
                System.out.println(gn.getValue().getFullName());
                gn.doAction();
            }

            System.out.println();
            System.out.println("The results of alternative: " + a.getName());
            System.out.println(printDependencyGraph());
            depGraph.removeAllDependencies();
        }


    }

    /**
     * *
     * Register an object as a listener to the port's events
     *
     * @param l object to be registered
     */
    public synchronized void addPSystemEventListener(SubjParametricSystemEventListener l) {
        listeners.add(l);
    }

    /**
     * Unregister an object as a listener
     *
     * @param l object to be removed as a listener
     */
    public synchronized void removePSystemEventListener(SubjParametricSystemEventListener l) {
        listeners.remove(l);
    }

    /*
     * Fire the port event
     * @param msg the message to be passed along with the event
     */
    protected synchronized void firePSystemEvent(String msg) {
        SubjParametricSystemEvent event = new SubjParametricSystemEvent(this, msg);
        for (SubjParametricSystemEventListener l : listeners) {
            l.handlePortEvent(event);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes.values()) {
            sb.append(n.toString());
        }

        for (SubjunctiveNode sn : subjNodes.values()) {
            sb.append(sn.toString());
        }

        return sb.toString();
    }

    @Override
    public void handleNodeEvent(NodeEvent ne) {
        System.out.println(ne.getMessage());
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public String getFullName() {
        return "";
    }

    @Override
    public Path getPath() {
        return null;
    }

    /**
     * Produce node from the ParseTree
     * Looks up the ParseTree in the map and generates the node
     * @param name of node to produce
     * @return Node object
     */
    private Node createNode(String name) {
        // look up the parse tree of the node to duplicate
        ParseTree nodeAST = nodeDefs.get(name);
        // walk the parse tree to realize the node
        ParseTreeWalker walker = new ParseTreeWalker();
        // createa node production listener
        NodeProductionListener nodeBuilder = new NodeProductionListener(this);
        // walk the parse tree and build the node
        walker.walk(nodeBuilder, nodeAST);

        return nodeBuilder.getCreatedNode();
    }

    /**
     * Produce a subjunctive node from the ParseTree
     * Looks up the ParseTree in the map and generates the node
     * @param name of subjunctive node to produce
     * @return SubjunctiveNode object
     */
    private SubjunctiveNode createSubjNode(String name) {
        // look up the parse tree of the node to duplicate
        ParseTree nodeAST = subjNodeDefs.get(name);
        // walk the parse tree to realize the node
        ParseTreeWalker walker = new ParseTreeWalker();
        // createa node production listener
        NodeProductionListener nodeBuilder = new NodeProductionListener(this);
        // walk the parse tree and build the node
        walker.walk(nodeBuilder, nodeAST);

        return nodeBuilder.getCreatedSubjNode();
    }

    /**
     * Get a subjunctive node by name
     * @param nodeName
     * @return the Subjunctive node for the corresponding name.
     */
    public SubjunctiveNode getSubjunctiveNode(String nodeName) {
        return subjNodes.get(nodeName);
    }

    /**
     * Add an alternative to the the parametric system
     * @param state 
     */
    public void addAlternative(SystemState state) {
        alternatives.put(state.getName(), state);
    }
}
