/* abc - The AspectBench Compiler
 * Copyright (C) 2007 Patrick Lam
 * Copyright (C) 2007 Eric Bodden
 *
 * This compiler is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This compiler is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this compiler, in the file LESSER-GPL;
 * if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package abc.tm.weaving.weaver.tmanalysis.stages;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import soot.Body;
import soot.Kind;
import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.CallGraphBuilder;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.pointer.DumbPointerAnalysis;
import soot.jimple.toolkits.pointer.LocalMustAliasAnalysis;
import soot.jimple.toolkits.pointer.LocalNotMayAliasAnalysis;
import soot.jimple.toolkits.thread.IThreadLocalObjectsAnalysis;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.UnitGraph;
import abc.main.Main;
import abc.tm.weaving.aspectinfo.TMGlobalAspectInfo;
import abc.tm.weaving.aspectinfo.TraceMatch;
import abc.tm.weaving.weaver.tmanalysis.query.ReachableShadowFinder;
import abc.tm.weaving.weaver.tmanalysis.query.SymbolShadowWithPTS;
import abc.tm.weaving.weaver.tmanalysis.stages.TMShadowTagger.SymbolShadowTag;
import abc.tm.weaving.weaver.tmanalysis.subanalyses.CannotTriggerFinalElimination;
import abc.tm.weaving.weaver.tmanalysis.subanalyses.ShadowMotion;
import abc.tm.weaving.weaver.tmanalysis.subanalyses.UnnecessaryShadowElimination;
import abc.tm.weaving.weaver.tmanalysis.util.ISymbolShadow;
import abc.tm.weaving.weaver.tmanalysis.util.ShadowsPerTMSplitter;

/**
 * IntraproceduralAnalysis: This analysis propagates tracematch
 * automaton states through the method.
 *
 * @author Patrick Lam
 * @author Eric Bodden
 */
public class IntraproceduralAnalysis extends AbstractAnalysisStage {
	
	protected final static boolean RUN_REAL_POINTS_TO = true;
	
	public static TMGlobalAspectInfo gai = (TMGlobalAspectInfo) Main.v().getAbcExtension().getGlobalAspectInfo();

	/**
	 * {@inheritDoc}
	 */
	protected void doAnalysis() {
		TMShadowTagger.v().apply();
		CallGraph cg; 
		@SuppressWarnings("unused") //maybe used later
		IThreadLocalObjectsAnalysis tloa = new IThreadLocalObjectsAnalysis() {
			public boolean isObjectThreadLocal(Value localOrRef,SootMethod sm) {
				//assume that any variable is thread-local;
				//THIS IS UNSAFE!
				return true;
			}
		};
		if(RUN_REAL_POINTS_TO) {
			cg = CallGraphAbstraction.v().abstractedCallGraph();
		} else {
			CallGraphBuilder cgb = new CallGraphBuilder(DumbPointerAnalysis.v());
			soot.Scene.v().setPointsToAnalysis(DumbPointerAnalysis.v());
			cg = cgb.getCallGraph();
			cgb.build();
		}
		
		Set reachableShadows = ReachableShadowFinder.v().reachableShadows(cg);
		Map tmNameToShadows = ShadowsPerTMSplitter.splitShadows(reachableShadows);
		
        boolean mayStartThreads = mayStartThreads();
        
        for (TraceMatch tm : (Collection<TraceMatch>)gai.getTraceMatches()) {
            if(mayStartThreads && !tm.isPerThread()) {
                System.err.println("#####################################################");
                System.err.println(" Application may start threads that execute shadows! ");
                System.err.println(" Tracematch "+tm.getName()+" is not per-thread!");
                System.err.println("#####################################################");
            }
            
        	Set<SootMethod> methodsWithShadows = new HashSet<SootMethod>();
        	Set<SymbolShadowWithPTS> thisTMsShadows = (Set<SymbolShadowWithPTS>) tmNameToShadows.get(tm.getName());
            for (SymbolShadowWithPTS s : thisTMsShadows) {
                SootMethod m = s.getContainer();
                methodsWithShadows.add(m);
            }

            for (SootMethod m : methodsWithShadows) {
                System.err.println("Analyzing: "+m+" on tracematch: "+tm.getName());
                
                UnitGraph g = new ExceptionalUnitGraph(m.retrieveActiveBody());
    			LocalMustAliasAnalysis localMustAliasAnalysis = new LocalMustAliasAnalysis(g);
				LocalNotMayAliasAnalysis localNotMayAliasAnalysis = new LocalNotMayAliasAnalysis(g);
                Map<Local,Stmt> tmLocalsToDefStatements = findTmLocalDefinitions(g,tm);

                boolean allRemoved = UnnecessaryShadowElimination.apply(tm, g, tmLocalsToDefStatements, localMustAliasAnalysis, localNotMayAliasAnalysis);
                
                if(!allRemoved) {
                    allRemoved = CannotTriggerFinalElimination.apply(tm, g, tmLocalsToDefStatements, localMustAliasAnalysis, localNotMayAliasAnalysis);

                    if(!allRemoved) {
                        ShadowMotion.apply(tm, g, tmLocalsToDefStatements, localMustAliasAnalysis, localNotMayAliasAnalysis);
                    }
                }

                System.err.println("Done analyzing: "+m+" on tracematch: "+tm.getName());    			
	        }
		}
        
//        //set shadow-points
//        Weaver weaver = Main.v().getAbcExtension().getWeaver();        
//        ShadowPointsSetter sps = new ShadowPointsSetter(weaver.getUnitBindings());
//        for (SootClass c : classesWithReroutetShadows) {
//            sps.setShadowPointsPass1(c);
//        }
	}

    private boolean mayStartThreads() {
        CallGraph callGraph = CallGraphAbstraction.v().abstractedCallGraph();
        for (Iterator iterator = callGraph.listener(); iterator.hasNext();) {
            Edge edge = (Edge) iterator.next();
            if(edge.kind().equals(Kind.THREAD)) {
                return true;
            }
        }
        return false;
    }
    
	private Map<Local, Stmt> findTmLocalDefinitions(UnitGraph g, TraceMatch tm) {
		
		Body b = g.getBody();
		
		Set<Local> boundLocals = new HashSet<Local>();
		
		//find all localc bound by shadows of the given tracematch		
		for (Unit u: b.getUnits()) {
            Stmt stmt = (Stmt)u;
			if(stmt.hasTag(SymbolShadowTag.NAME)) {
				SymbolShadowTag tag = (SymbolShadowTag) stmt.getTag(SymbolShadowTag.NAME);
				Set<ISymbolShadow> matchesForTracematch = tag.getMatchesForTracematch(tm);
				for (ISymbolShadow shadow : matchesForTracematch) {
					boundLocals.addAll(shadow.getAdviceLocals());
				}
			}
		}
		
		Map<Local,Stmt> localToStmtAfterDefStmt = new HashMap<Local, Stmt>();
		
        for (Unit u: b.getUnits()) {
            Stmt stmt = (Stmt)u;
            for (soot.ValueBox vb : (Collection<soot.ValueBox>)stmt.getDefBoxes()) {
                soot.Value v = vb.getValue();
                if(boundLocals.contains(v)) {
                    //have a definition of v already!
                    if(localToStmtAfterDefStmt.containsKey(v)) {
                        throw new RuntimeException("multiple defs");
                    }
                    
                	//we know that such def statements always have the form "adviceLocal = someLocal;",
                	//hence taking the first successor is always sound
                	localToStmtAfterDefStmt.put((Local)v, (Stmt)g.getSuccsOf(stmt).get(0));
                }
            }			
		}
		
		return localToStmtAfterDefStmt;		
	}

	
	
	//singleton pattern
	
	protected static IntraproceduralAnalysis instance;

	private IntraproceduralAnalysis() {}
	
	public static IntraproceduralAnalysis v() {
		if(instance==null) {
			instance = new IntraproceduralAnalysis();
		}
		return instance;		
	}
	
	/**
	 * Frees the singleton object. 
	 */
	public static void reset() {
		instance = null;
	}

}