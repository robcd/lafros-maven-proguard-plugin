This is a plug-in for the Apache Maven build tool, providing a goal that 'liberates' the project's jar artifact, in advance of its creation, from specified library dependencies which are typically large and only sparsely populated with the classes which are actually required.

This is achieved by copying those classes to where they will be added to the artifact by the standard jar plug-in in the usual way. Other dependencies which also depend on the above ones may also be specified, so that the classes which they themselves require will also be included.

The plug-in is therefore of interest to anyone writing applets or Java Web Start (JNLP) applications in Scala, which by their nature have dependencies of exactly the type described above.

For further details, please visit http://lafros.com/maven/plugins/proguard.