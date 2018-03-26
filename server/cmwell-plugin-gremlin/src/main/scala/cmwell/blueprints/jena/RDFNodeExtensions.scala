/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package cmwell.blueprints.jena

import org.apache.jena.rdf.model.RDFNode

/**
  * Created by yaakov on 6/2/15.
  */
object Extensions {
  implicit class RDFNodeExtensions(rdfNode: RDFNode) {
    def id = {
      if (rdfNode.isResource) {
        rdfNode.asResource.getURI
      } else {
        if (rdfNode.isLiteral) rdfNode.asLiteral.getValue else rdfNode.toString
      }
    }

    def isSameAs(otherOne: RDFNode) = rdfNode.id == otherOne.id
  }
}
