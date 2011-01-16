package com.bigdata.bop.rdf.joinGraph;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;

import junit.framework.TestCase2;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.openrdf.query.algebra.Compare.CompareOp;
import org.openrdf.query.algebra.MathExpr.MathOp;

import com.bigdata.bop.BOp;
import com.bigdata.bop.BOpContextBase;
import com.bigdata.bop.BOpIdFactory;
import com.bigdata.bop.Constant;
import com.bigdata.bop.IBindingSet;
import com.bigdata.bop.IConstraint;
import com.bigdata.bop.IPredicate;
import com.bigdata.bop.IVariable;
import com.bigdata.bop.NV;
import com.bigdata.bop.PipelineOp;
import com.bigdata.bop.Var;
import com.bigdata.bop.IPredicate.Annotations;
import com.bigdata.bop.constraint.NEConstant;
import com.bigdata.bop.controller.JoinGraph;
import com.bigdata.bop.controller.JoinGraph.JGraph;
import com.bigdata.bop.controller.JoinGraph.Path;
import com.bigdata.bop.engine.BOpStats;
import com.bigdata.bop.engine.IRunningQuery;
import com.bigdata.bop.engine.QueryEngine;
import com.bigdata.bop.engine.QueryLog;
import com.bigdata.bop.fed.QueryEngineFactory;
import com.bigdata.journal.Journal;
import com.bigdata.rdf.internal.XSDIntIV;
import com.bigdata.rdf.internal.constraints.CompareBOp;
import com.bigdata.rdf.internal.constraints.MathBOp;
import com.bigdata.rdf.model.BigdataURI;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.spo.SPOPredicate;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.DataLoader;
import com.bigdata.rdf.store.DataLoader.ClosureEnum;
import com.bigdata.relation.accesspath.IAsynchronousIterator;
import com.bigdata.relation.rule.IRule;
import com.bigdata.relation.rule.Rule;
import com.bigdata.relation.rule.eval.DefaultEvaluationPlan2;
import com.bigdata.relation.rule.eval.IRangeCountFactory;

/**
 * Unit tests for runtime query optimization using {@link JoinGraph} and the
 * "BSBM" test set.
 * <p>
 * Note: When running large queries, be sure to provide a sufficient heap, set
 * the -server flag, etc.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestJoinGraphOnBSBMData extends TestCase2 {

    /**
     * 
     */
    public TestJoinGraphOnBSBMData() {
    }

	/**
	 * @param name
	 */
	public TestJoinGraphOnBSBMData(String name) {
		super(name);
	}

	@Override
	public Properties getProperties() {

		final Properties p = new Properties(super.getProperties());

//		p.setProperty(Journal.Options.BUFFER_MODE, BufferMode.Transient
//				.toString());

//		p.setProperty(AbstractTripleStore.Options.QUADS_MODE, "true");
		
		/*
		 * Don't compute closure in the data loader since it does TM, not
		 * database at once closure.
		 */
		p.setProperty(DataLoader.Options.CLOSURE, ClosureEnum.None.toString());

		return p;

	}

    private Journal jnl;
    
    private AbstractTripleStore database;

    /** The initial sampling limit. */
    private final int limit = 100;
    
    /** The #of edges considered for the initial paths. */
    private final int nedges = 2;

    private QueryEngine queryEngine; 

	private String namespace;

	/**
	 * When true, do a warm up run of the plan generated by the static query
	 * optimizer.
	 */
	private final boolean warmUp = false;
	
	/**
	 * The #of times to run each query. Use N GT ONE (1) if you want to converge
	 * onto the hot query performance.
	 */
	private final int ntrials = 3;

	/**
	 * When <code>true</code> runs the query in the given order.
	 */
	private final boolean runGivenOrder = false;
	
	/**
	 * When <code>true</code> runs the dynamic query optimizer and then evaluates
	 * the generated query plan.
	 */
	private final boolean runRuntimeQueryOptimizer = true;
	
	/**
	 * When <code>true</code> runs the static query optimizer and then evaluates
	 * the generated query plan.
	 */
	private final boolean runStaticQueryOptimizer = true;
	
	/**
	 * Loads LUBM U1 into a triple store.
	 */
	protected void setUp() throws Exception {

//		QueryLog.logTableHeader();
		
		super.setUp();

//		System.err.println(UUID.randomUUID().toString());
//		System.exit(0);
		
		final Properties properties = getProperties();

		final File file;
		{
			/*
			 * Use a specific file generated by some external process.
			 */
			final long pc = 284826; // BSBM 100M
//			final long pc = 566496; // BSBM 200M
			file = new File("/data/bsbm/bsbm_"+pc+"/bigdata-bsbm.RW.jnl");
			namespace = "BSBM_"+pc;
		}
		
		properties.setProperty(Journal.Options.FILE, file.toString());

//		properties.setProperty(Journal.Options.BUFFER_MODE,BufferMode.DiskRW.toString());

//		file.delete();
		
		if (!file.exists()) {

            fail("File not found: " + file);
		    
//			jnl = new Journal(properties);
//
//			final AbstractTripleStore tripleStore = new LocalTripleStore(jnl,
//					namespace, ITx.UNISOLATED, properties);
//
//			// Create the KB instance.
//			tripleStore.create();
//
//			tripleStore.getDataLoader().loadFiles(
//					new File("/root/Desktop/Downloads/barData/barData.trig"),
//					null/* baseURI */, RDFFormat.TRIG, null/* defaultGraph */,
//					null/* filter */);
//
//			// Truncate the journal (trim its size).
//			jnl.truncate();
//			
//			// Commit the journal.
//			jnl.commit();
//
//			// Close the journal.
//			jnl.close();
			
		}

		// Open the test resource.
		jnl = new Journal(properties);

		queryEngine = QueryEngineFactory
				.getQueryController(jnl/* indexManager */);

		database = (AbstractTripleStore) jnl.getResourceLocator().locate(
				namespace, jnl.getLastCommitTime());

		if (database == null)
			throw new RuntimeException("Not found: " + namespace);

	}

	protected void tearDown() throws Exception {

		if (database != null) {
			database = null;
		}
		
		if (queryEngine != null) {
			queryEngine.shutdownNow();
			queryEngine = null;
		}

		if(jnl != null) {
			jnl.close();
			jnl = null;
		}
		
		super.tearDown();
		
	}

    /**
     * BSBM Q5
     * 
     * <pre>
     * PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
     * PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
     * PREFIX bsbm: <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/>
     * 
     * SELECT DISTINCT ?product ?productLabel
     * WHERE { 
     *     ?product rdfs:label ?productLabel .
     *     FILTER (<http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> != ?product)
     *     <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productFeature ?prodFeature .
     *     ?product bsbm:productFeature ?prodFeature .
     *     <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productPropertyNumeric1 ?origProperty1 .
     *     ?product bsbm:productPropertyNumeric1 ?simProperty1 .
     *     FILTER (?simProperty1 < (?origProperty1 + 120) && ?simProperty1 > (?origProperty1 - 120))
     *     <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productPropertyNumeric2 ?origProperty2 .
     *     ?product bsbm:productPropertyNumeric2 ?simProperty2 .
     *     FILTER (?simProperty2 < (?origProperty2 + 170) && ?simProperty2 > (?origProperty2 - 170))
     * }
     * ORDER BY ?productLabel
     * LIMIT 5
     * </pre>
     * @throws Exception
     */
	public void test_bsbm_q5() throws Exception {

		/*
		 * Resolve terms against the lexicon.
		 */
		final BigdataValueFactory valueFactory = database.getLexiconRelation()
				.getValueFactory();

        final String rdfs = "http://www.w3.org/2000/01/rdf-schema#";
//        final String rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
        final String bsbm = "http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/vocabulary/";

//        final BigdataURI rdfType = valueFactory.createURI(rdf + "type");

        final BigdataURI rdfsLabel = valueFactory.createURI(rdfs + "label");

        final BigdataURI productFeature = valueFactory.createURI(bsbm
                + "productFeature");

        final BigdataURI productPropertyNumeric1 = valueFactory.createURI(bsbm
                + "productPropertyNumeric1");

        final BigdataURI productPropertyNumeric2 = valueFactory.createURI(bsbm
                + "productPropertyNumeric2");

        final BigdataURI product53999 = valueFactory
                .createURI("http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999");
		
        final BigdataValue[] terms = new BigdataValue[] { rdfsLabel,
                productFeature, productPropertyNumeric1,
                productPropertyNumeric2, product53999 };

		// resolve terms.
		database.getLexiconRelation()
				.addTerms(terms, terms.length, true/* readOnly */);

		{
			for (BigdataValue tmp : terms) {
				System.out.println(tmp + " : " + tmp.getIV());
				if (tmp.getIV() == null)
					throw new RuntimeException("Not defined: " + tmp);
			}
		}

		final IPredicate[] preds;
		final IPredicate p0, p1, p2, p3, p4, p5, p6;
		{
		    final IVariable product = Var.var("product");
		    final IVariable productLabel = Var.var("productLabel"); 
		    final IVariable prodFeature= Var.var("prodFeature"); 
		    final IVariable simProperty1 = Var.var("simProperty1"); 
		    final IVariable simProperty2 = Var.var("simProperty2"); 
		    final IVariable origProperty1 = Var.var("origProperty1"); 
		    final IVariable origProperty2 = Var.var("origProperty2"); 

			// The name space for the SPO relation.
			final String[] spoRelation = new String[] { namespace + ".spo" };

//			// The name space for the Lexicon relation.
//			final String[] lexRelation = new String[] { namespace + ".lex" };

			final long timestamp = jnl.getLastCommitTime();

			int nextId = 0;

//			?product rdfs:label ?productLabel .
//		    FILTER (<http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> != ?product)
			p0 = new SPOPredicate(new BOp[] {//
			        product,
					new Constant(rdfsLabel.getIV()),
					productLabel//
					},//
					new NV(BOp.Annotations.BOP_ID, nextId++),//
					new NV(Annotations.TIMESTAMP, timestamp),//
					new NV(IPredicate.Annotations.RELATION_NAME, spoRelation),//
                    /*
                     * Note: In order to code up this query for the runtime
                     * query optimizer we need to attach the constraint
                     * (product53999 != ?product) to the access path rather than
                     * the join (the RTO does not accept join operators, just
                     * predicates). The RTO knows to look for the CONSTRAINTS on
                     * the IPredicate and apply them to the constructed join
                     * operator.
                     */
                    new NV(Annotations.CONSTRAINTS,
                            new IConstraint[] {//
                            new NEConstant(product, new Constant(product53999
                                    .getIV())) //
                            })//
			);
						
//          <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productFeature ?prodFeature .
			p1 = new SPOPredicate(new BOp[] { //
			        new Constant(product53999.getIV()),//
                    new Constant(productFeature.getIV()),//
                    prodFeature//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation)
			);
			
//          ?product bsbm:productFeature ?prodFeature .
            p2 = new SPOPredicate(new BOp[] { //
                    product,//
                    new Constant(productFeature.getIV()),//
                    prodFeature//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation)
            );
            
//          <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productPropertyNumeric1 ?origProperty1 .
            p3 = new SPOPredicate(new BOp[] { //
                    new Constant(product53999.getIV()),//
                    new Constant(productPropertyNumeric1.getIV()),//
                    origProperty1//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation)
            );
            
//          ?product bsbm:productPropertyNumeric1 ?simProperty1 .
//          FILTER (?simProperty1 < (?origProperty1 + 120) && ?simProperty1 > (?origProperty1 - 120))
            p4 = new SPOPredicate(new BOp[] { //
                    product,//
                    new Constant(productPropertyNumeric1.getIV()),//
                    simProperty1//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation),
                    new NV(Annotations.CONSTRAINTS,
                            new IConstraint[] {//
                                    new CompareBOp(new BOp[] {
                                            simProperty1,
                                            new MathBOp(origProperty1,
                                                    new Constant(new XSDIntIV(
                                                            120)),
                                                    MathOp.PLUS) }, NV
                                            .asMap(new NV[] { new NV(
                                                    CompareBOp.Annotations.OP,
                                                    CompareOp.LT) })),//
                                    new CompareBOp(new BOp[] {
                                            simProperty1,
                                            new MathBOp(origProperty1,
                                                    new Constant(new XSDIntIV(
                                                            120)),
                                                    MathOp.MINUS) }, NV
                                            .asMap(new NV[] { new NV(
                                                    CompareBOp.Annotations.OP,
                                                    CompareOp.GT) })),//
                            })//
            );

//          <http://www4.wiwiss.fu-berlin.de/bizer/bsbm/v01/instances/dataFromProducer1092/Product53999> bsbm:productPropertyNumeric2 ?origProperty2 .
            p5 = new SPOPredicate(new BOp[] { //
                    new Constant(product53999.getIV()),//
                    new Constant(productPropertyNumeric2.getIV()),//
                    origProperty2//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation)
            );

//          ?product bsbm:productPropertyNumeric2 ?simProperty2 .
//          FILTER (?simProperty2 < (?origProperty2 + 170) && ?simProperty2 > (?origProperty2 - 170))
            p6 = new SPOPredicate(new BOp[] { //
                    product,//
                    new Constant(productPropertyNumeric2.getIV()),//
                    simProperty2//
                    },//
                    new NV(BOp.Annotations.BOP_ID, nextId++),//
                    new NV(Annotations.TIMESTAMP, timestamp),//
                    new NV(IPredicate.Annotations.RELATION_NAME, spoRelation),
                    new NV(Annotations.CONSTRAINTS,
                            new IConstraint[] {//
                                    new CompareBOp(new BOp[] {
                                            simProperty2,
                                            new MathBOp(origProperty2,
                                                    new Constant(new XSDIntIV(
                                                            170)),
                                                    MathOp.PLUS) }, NV
                                            .asMap(new NV[] { new NV(
                                                    CompareBOp.Annotations.OP,
                                                    CompareOp.LT) })),//
                                    new CompareBOp(new BOp[] {
                                            simProperty2,
                                            new MathBOp(origProperty2,
                                                    new Constant(new XSDIntIV(
                                                            170)),
                                                    MathOp.MINUS) }, NV
                                            .asMap(new NV[] { new NV(
                                                    CompareBOp.Annotations.OP,
                                                    CompareOp.GT) })),//
                            })//
            );

			// the vertices of the join graph (the predicates).
			preds = new IPredicate[] { p0, p1, p2, p3, p4, p5, p6 };

		}

		doTest(preds);
		
	}

	/**
	 * 
	 * @param preds
	 * @throws Exception
	 * 
	 * @todo To actually test anything this needs to compare the results (or at
	 *       least the #of results). We could also test for known good join
	 *       orders as generated by the runtime optimizer, but that requires a
	 *       known data set (e.g., U1 or U50) and non-random sampling.
	 * 
	 * @todo This is currently providing a "hot run" comparison by a series of
	 *       trials. This means that the IO costs are effectively being wiped
	 *       away, assuming that the file system cache is larger than the data
	 *       set. The other way to compare performance is a cold cache / cold
	 *       JVM run using the known solutions produced by the runtime versus
	 *       static query optimizers.
	 */
	private void doTest(final IPredicate[] preds) throws Exception {

		if (warmUp)
			runQuery("Warmup", queryEngine, runStaticQueryOptimizer(preds));

		/*
		 * Run the runtime query optimizer once (its cost is not counted
		 * thereafter).
		 */
		final IPredicate[] runtimePredOrder = runRuntimeQueryOptimizer(preds);

		long totalRuntimeTime = 0;
		long totalStaticTime = 0;
		
		for (int i = 0; i < ntrials; i++) {

			final String RUNTIME = getName() + " : runtime["+i+"] :";

			final String STATIC =  getName() + " : static ["+i+"] :";

			final String GIVEN =  getName() + " : given  ["+i+"] :";

			if (runGivenOrder) {

				runQuery(GIVEN, queryEngine, preds);
				
			}

			if (runStaticQueryOptimizer) {

				totalStaticTime += runQuery(STATIC, queryEngine,
						runStaticQueryOptimizer(preds));

			}

			if (runRuntimeQueryOptimizer) {

				/*
				 * Run the runtime query optimizer each time (its overhead is
				 * factored into the running comparison of the two query
				 * optimizers).
				 */
//				final IPredicate[] runtimePredOrder = runRuntimeQueryOptimizer(new JGraph(
//						preds));

				// Evaluate the query using the selected join order.
				totalRuntimeTime += runQuery(RUNTIME, queryEngine,
						runtimePredOrder);

			}

		}

		if(runStaticQueryOptimizer&&runRuntimeQueryOptimizer) {
			System.err.println(getName() + " : Total times" + //
					": static=" + totalStaticTime + //
					", runtime=" + totalRuntimeTime + //
					", delta(static-runtime)=" + (totalStaticTime - totalRuntimeTime));
		}

	}
	
	/**
	 * Apply the runtime query optimizer.
	 * <p>
	 * Note: This temporarily raises the {@link QueryLog} log level during
	 * sampling to make the log files cleaner (this can not be done for a
	 * deployed system since the logger level is global and there are concurrent
	 * query mixes).
	 * 
	 * @return The predicates in order as recommended by the runtime query
	 *         optimizer.
	 * 
	 * @throws Exception
	 */
	private IPredicate[] runRuntimeQueryOptimizer(final IPredicate[] preds) throws Exception {

		final Logger tmp = Logger.getLogger(QueryLog.class);
		final Level oldLevel = tmp.getEffectiveLevel();
		tmp.setLevel(Level.WARN);

		try {

			final JGraph g = new JGraph(preds);
			
			final Path p = g.runtimeOptimizer(queryEngine, limit, nedges);

//			System.err.println(getName() + " : runtime optimizer join order "
//					+ Arrays.toString(Path.getVertexIds(p.edges)));

			return p.getPredicates();

		} finally {

			tmp.setLevel(oldLevel);

		}

	}

	/**
	 * Apply the static query optimizer.
	 * 
	 * @return The predicates in order as recommended by the static query
	 *         optimizer.
	 */
	private IPredicate[] runStaticQueryOptimizer(final IPredicate[] preds) {

		final BOpContextBase context = new BOpContextBase(queryEngine);

		final IRule rule = new Rule("tmp", null/* head */, preds, null/* constraints */);

		final DefaultEvaluationPlan2 plan = new DefaultEvaluationPlan2(
				new IRangeCountFactory() {

					public long rangeCount(final IPredicate pred) {
						return context.getRelation(pred).getAccessPath(pred)
								.rangeCount(false);
					}

				}, rule);

		// evaluation plan order.
		final int[] order = plan.getOrder();

		final int[] ids = new int[order.length];
		
		final IPredicate[] out = new IPredicate[order.length];

		for (int i = 0; i < order.length; i++) {

			out[i] = preds[order[i]];
			
			ids[i] = out[i].getId();

		}
		
//		System.err.println(getName() + " :  static optimizer join order "
//				+ Arrays.toString(ids));
		
		return out;
		
	}

	/**
	 * Run a query joining a set of {@link IPredicate}s in the given join order.
	 * 
	 * @return The elapsed query time (ms).
	 */
	private static long runQuery(final String msg,
			final QueryEngine queryEngine, final IPredicate[] predOrder)
			throws Exception {

		final BOpIdFactory idFactory = new BOpIdFactory();

		final int[] ids = new int[predOrder.length];
		
		for(int i=0; i<ids.length; i++) {
		
			final IPredicate<?> p = predOrder[i];
			
			idFactory.reserve(p.getId());
			
			ids[i] = p.getId();
			
		}

		final PipelineOp queryOp = JoinGraph.getQuery(idFactory, predOrder);

		// submit query to runtime optimizer.
		final IRunningQuery q = queryEngine.eval(queryOp);

		// drain the query results.
		long nout = 0;
		long nchunks = 0;
		final IAsynchronousIterator<IBindingSet[]> itr = q.iterator();
		try {
			while (itr.hasNext()) {
				final IBindingSet[] chunk = itr.next();
				nout += chunk.length;
				nchunks++;
			}
		} finally {
			itr.close();
		}

		// check the Future for the query.
		q.get();

		// show the results.
		final BOpStats stats = q.getStats().get(queryOp.getId());

		System.err.println(msg + " : ids=" + Arrays.toString(ids)
				+ ", elapsed=" + q.getElapsed() + ", nout=" + nout
				+ ", nchunks=" + nchunks + ", stats=" + stats);
		
		return q.getElapsed();

	}

}
