/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.spi.gdsimpl

import org.neo4j.cypher.internal.spi._
import org.neo4j.graphdb._
import org.neo4j.kernel.GraphDatabaseAPI
import org.neo4j.kernel.api._
import collection.JavaConverters._
import org.neo4j.graphdb.DynamicRelationshipType.withName
import org.neo4j.cypher.{CouldNotCreateConstraintException, EntityNotFoundException, CouldNotDropIndexException,
IndexAlreadyDefinedException}
import org.neo4j.tooling.GlobalGraphOperations
import collection.mutable
import org.neo4j.kernel.impl.api.index.IndexDescriptor
import org.neo4j.helpers.collection.IteratorUtil
import org.neo4j.kernel.api.operations.KeyNameLookup
import scala.Some
import org.neo4j.kernel.api.exceptions.LabelNotFoundKernelException
import org.neo4j.kernel.api.exceptions.schema.{ConstraintCreationKernelException, DropIndexFailureException,
SchemaKernelException}

class TransactionBoundQueryContext(graph: GraphDatabaseAPI, tx: Transaction, ctx: StatementContext) extends QueryContext {

  def setLabelsOnNode(node: Long, labelIds: Iterable[Long]): Int = labelIds.foldLeft(0) {
    case (count, labelId) => if (ctx.nodeAddLabel(node, labelId)) count + 1 else count
  }

  def close(success: Boolean) {
    ctx.close()

    if (success)
      tx.success()
    else
      tx.failure()
    tx.finish()
  }

  def createNode(): Node =
    graph.createNode()

  def createRelationship(start: Node, end: Node, relType: String) =
    start.createRelationshipTo(end, withName(relType))

  def getLabelName(id: Long) =
    ctx.labelGetName(id)

  def getLabelsForNode(node: Long) =
    ctx.nodeGetLabels(node).asScala.map(_.asInstanceOf[Long])

  override def isLabelSetOnNode(label: Long, node: Long) =
    ctx.nodeHasLabel(node, label)

  def getOrCreateLabelId(labelName: String) =
    ctx.labelGetOrCreateForName(labelName)


  def getLabelId(labelName: String): Option[Long] = try {
    Some(ctx.labelGetForName(labelName))
  } catch {
    case _: LabelNotFoundKernelException => None
  }


  def getRelationshipsFor(node: Node, dir: Direction, types: Seq[String]) = types match {
    case Seq() => node.getRelationships(dir).asScala
    case _     => node.getRelationships(dir, types.map(withName): _*).asScala
  }

  def getTransaction = tx

  def exactIndexSearch(index: IndexDescriptor, value: Any) =
    ctx.nodesGetFromIndexLookup(index, value).asScala.map((id: java.lang.Long) => nodeOps.getById(id))

  val nodeOps = new NodeOperations

  val relationshipOps = new RelationshipOperations

  def removeLabelsFromNode(node: Long, labelIds: Iterable[Long]): Int = labelIds.foldLeft(0) {
    case (count, labelId) => if (ctx.nodeRemoveLabel(node, labelId)) count + 1 else count
  }

  def getNodesByLabel(id: Long): Iterator[Node] = ctx.nodesGetForLabel(id).asScala.map(nodeOps.getById(_))

  class NodeOperations extends BaseOperations[Node] {
    def delete(obj: Node) {
      obj.delete()
    }

    def getById(id: Long) = try {
      graph.getNodeById(id)
    } catch {
      case e: NotFoundException => throw new EntityNotFoundException(s"Node with id $id", e)
      case e: RuntimeException  => throw e
    }

    def all: Iterator[Node] = GlobalGraphOperations.at(graph).getAllNodes.iterator().asScala

    def indexGet(name: String, key: String, value: Any): Iterator[Node] =
      graph.index.forNodes(name).get(key, value).iterator().asScala

    def indexQuery(name: String, query: Any): Iterator[Node] =
      graph.index.forNodes(name).query(query).iterator().asScala
  }

  class RelationshipOperations extends BaseOperations[Relationship] {
    def delete(obj: Relationship) {
      obj.delete()
    }

    def getById(id: Long) = graph.getRelationshipById(id)

    def all: Iterator[Relationship] =
      GlobalGraphOperations.at(graph).getAllRelationships.iterator().asScala

    def indexGet(name: String, key: String, value: Any): Iterator[Relationship] =
      graph.index.forRelationships(name).get(key, value).iterator().asScala

    def indexQuery(name: String, query: Any): Iterator[Relationship] =
      graph.index.forRelationships(name).query(query).iterator().asScala
  }

  def getOrCreatePropertyKeyId(propertyKey: String) =
    ctx.propertyKeyGetOrCreateForName(propertyKey)

  def getPropertyKeyId(propertyKey: String) =
    ctx.propertyKeyGetForName(propertyKey)

  def addIndexRule(labelIds: Long, propertyKeyId: Long) {
    try {
      ctx.indexCreate(labelIds, propertyKeyId)
    } catch {
      case e: SchemaKernelException =>
        val labelName = getLabelName(labelIds)
        val propName = ctx.propertyKeyGetName(propertyKeyId)
        throw new IndexAlreadyDefinedException(labelName, propName, e)
    }
  }

  def dropIndexRule(labelId: Long, propertyKeyId: Long) {
    try {
      ctx.indexDrop(new IndexDescriptor(labelId, propertyKeyId))
    } catch {
      case e: DropIndexFailureException =>
        throw new CouldNotDropIndexException(e.getUserMessage(new KeyNameLookup(ctx)), e)
    }
  }

  def upgrade(context: QueryContext): LockingQueryContext = new RepeatableReadQueryContext(context, new Locker {
    private val locks = new mutable.ListBuffer[Lock]

    def releaseAllLocks() {
      locks.foreach(_.release())
    }

    def acquireLock(p: PropertyContainer) {
      locks += tx.acquireWriteLock(p)
    }
  })

  abstract class BaseOperations[T <: PropertyContainer] extends Operations[T] {
    def getProperty(obj: T, propertyKey: String) = obj.getProperty(propertyKey, null)

    def hasProperty(obj: T, propertyKey: String) = obj.hasProperty(propertyKey)

    def propertyKeys(obj: T) = obj.getPropertyKeys.asScala

    def removeProperty(obj: T, propertyKey: String) {
      obj.removeProperty(propertyKey)
    }

    def setProperty(obj: T, propertyKey: String, value: Any) {
      obj.setProperty(propertyKey, value)
    }
  }

  def getOrCreateFromSchemaState[K, V](key: K, creator: => V) = {
    val javaCreator = new org.neo4j.helpers.Function[K, V]() {
      def apply(key: K) = creator
    }
    ctx.schemaStateGetOrCreate(key, javaCreator)
  }

  def schemaStateContains(key: String) = ctx.schemaStateContains(key)

  def createUniqueConstraint(labelId: Long, propertyKeyId: Long) {
    try {
      ctx.uniquenessConstraintCreate(labelId, propertyKeyId)
    } catch {
        case e: ConstraintCreationKernelException =>
          throw new CouldNotCreateConstraintException(e.getUserMessage(new KeyNameLookup(ctx)), e)
    }
  }

  def dropUniqueConstraint(labelId: Long, propertyKeyId: Long) {
    val constraint = IteratorUtil.single(ctx.constraintsGetForLabelAndPropertyKey(labelId, propertyKeyId))
    ctx.constraintDrop(constraint)
  }
}