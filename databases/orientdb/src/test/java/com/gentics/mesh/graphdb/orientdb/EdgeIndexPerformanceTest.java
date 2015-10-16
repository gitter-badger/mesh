package com.gentics.mesh.graphdb.orientdb;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.orientechnologies.orient.core.index.OCompositeKey;
import com.orientechnologies.orient.core.index.OIndex;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.orient.OrientEdgeType;
import com.tinkerpop.blueprints.impls.orient.OrientGraphFactory;
import com.tinkerpop.blueprints.impls.orient.OrientGraphNoTx;
import com.tinkerpop.blueprints.impls.orient.OrientVertex;
import com.tinkerpop.blueprints.impls.orient.OrientVertexType;

public class EdgeIndexPerformanceTest {

	private static OrientGraphFactory factory = new OrientGraphFactory("memory:tinkerpop");

	private final static int nDocuments = 1000;
	private final static int nChecks = 4000;

	private static List<OrientVertex> items;
	private static OrientVertex root;

	@BeforeClass
	public static void setupDatabase() {
		setupTypesAndIndices(factory);

		root = createRoot(factory);
		items = createData(root, factory, nDocuments);
	}

	private static void setupTypesAndIndices(OrientGraphFactory factory2) {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			//g.setUseClassForEdgeLabel(true);
			g.setUseLightweightEdges(false);
			g.setUseVertexFieldsForEdgeLabels(false);
		} finally {
			g.shutdown();
		}

		try {
			g = factory.getNoTx();

			OrientEdgeType e = g.getEdgeType("E");
			e.createProperty("in", OType.LINK);
			e.createProperty("out", OType.LINK);
			e.createIndex("edge.has_item", OClass.INDEX_TYPE.UNIQUE_HASH_INDEX, "out", "in");

			OrientVertexType v = g.createVertexType("root", "V");
			v.createProperty("name", OType.STRING);

			v = g.createVertexType("item", "V");
			v.createProperty("name", OType.STRING);

		} finally {
			g.shutdown();
		}

	}

	private static List<OrientVertex> createData(OrientVertex root, OrientGraphFactory factory, int count) {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			System.out.println("Creating {" + count + "} items.");
			List<OrientVertex> items = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				OrientVertex item = g.addVertex("class:item");
				item.setProperty("name", "item_" + i);
				items.add(item);
				root.addEdge("HAS_ITEM", item, "class:E.edge");
			}
			return items;
		} finally {
			g.shutdown();
		}
	}

	private static OrientVertex createRoot(OrientGraphFactory factory) {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			OrientVertex root = g.addVertex("class:root");
			root.setProperty("name", "root vertex");
			return root;
		} finally {
			g.shutdown();
		}
	}

	@Test
	public void testEdgeIndexViaRootGetEdgesWithoutTarget() throws Exception {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			long start = System.currentTimeMillis();
			for (int i = 0; i < nChecks; i++) {
				OrientVertex randomDocument = items.get((int) (Math.random() * items.size()));
				Iterable<Edge> edges = root.getEdges(Direction.OUT, "HAS_ITEM");
				boolean found = false;
				for (Edge edge : edges) {
					if (edge.getVertex(Direction.IN).equals(randomDocument)) {
						found = true;
						break;
					}
				}
				assertTrue(found);
			}
			long dur = System.currentTimeMillis() - start;
			System.out.println("[root.getEdges - iterating] Duration: " + dur);
			System.out.println("[root.getEdges - iterating] Duration per lookup: " + ((double) dur / (double) nChecks));
		} finally {
			g.shutdown();
		}
	}

	@Test
	public void testEdgeIndexViaRootGetEdges() throws Exception {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			long start = System.currentTimeMillis();
			for (int i = 0; i < nChecks; i++) {
				OrientVertex randomDocument = items.get((int) (Math.random() * items.size()));
				Iterable<Edge> edges = root.getEdges(randomDocument, Direction.OUT, "HAS_ITEM");
				assertTrue(edges.iterator().hasNext());
			}
			long dur = System.currentTimeMillis() - start;
			System.out.println("[root.getEdges] Duration: " + dur);
			System.out.println("[root.getEdges] Duration per lookup: " + ((double) dur / (double) nChecks));
		} finally {
			g.shutdown();
		}
	}

	@Test
	public void testEdgeIndexViaGraphGetEdges() throws Exception {
		OrientGraphNoTx g = factory.getNoTx();

		for (OIndex<?> index : g.getRawGraph().getMetadata().getIndexManager().getIndexes()) {
			System.out.println(index.getName());
		}
		//		OIndex<?> index = g.getRawGraph().getMetadata().getIndexManager().getIndex("edge.has_item");
		//		assertNotNull("Index could not be found", index);
		try {
			long start = System.currentTimeMillis();
			for (int i = 0; i < nChecks; i++) {
				OrientVertex randomDocument = items.get((int) (Math.random() * items.size()));
				Iterable<Edge> edges = g.getEdges("edge.has_item", new OCompositeKey(root.getId(), randomDocument.getId()));
				assertTrue(edges.iterator().hasNext());
			}
			long dur = System.currentTimeMillis() - start;
			System.out.println("[graph.getEdges] Duration: " + dur);
			System.out.println("[graph.getEdges] Duration per lookup: " + ((double) dur / (double) nChecks));
		} finally {
			g.shutdown();
		}
	}

	@Test
	public void testEdgeIndexViaQuery() throws Exception {
		OrientGraphNoTx g = factory.getNoTx();
		try {
			System.out.println("Checking edge");
			long start = System.currentTimeMillis();
			for (int i = 0; i < nChecks; i++) {
				OrientVertex randomDocument = items.get((int) (Math.random() * items.size()));

				OCommandSQL cmd = new OCommandSQL("select from index:edge.has_item where key=?");
				OCompositeKey key = new OCompositeKey(root.getId(), randomDocument.getId());

				assertTrue(((Iterable<Vertex>) g.command(cmd).execute(key)).iterator().hasNext());
			}
			long dur = System.currentTimeMillis() - start;
			System.out.println("[query] Duration: " + dur);
			System.out.println("[query] Duration per lookup: " + ((double) dur / (double) nChecks));
		} finally {
			g.shutdown();
		}
	}

}