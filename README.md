<h3>Defunct: use `Cleaner` instead!</h3>

This was just a little side project. Later I found out that there already is `sun.misc.Cleaner` and we should get `java.util.Cleaner` with Java 9. So use that instead. Maybe this can still be used if you want to learn how to use PhantomReferences, but don't use it in production.

<h3><i>Java's PhantomReferences made easy.</i></h3>

Do you know this situation: You have some java class and you need to cleanup after it was garbage collected? 
You tried finalize but it didn't work you you have read that finalize should not be used. 
The interface Cleanup allows you to register cleanup-code for any object. Implementing the interface Cleanup makes it even simpler.

An example is included and can be used like a tutorial:
[Example.java](https://github.com/claudemartin/java-cleanup/blob/master/Cleanup/tests/ch/claude_martin/cleanup/Example.java)

Pros, Cons and Pitfalls can be found in the [javadoc of Cleanup](http://claude-martin.ch/java-cleanup/doc/ch/claude_martin/cleanup/Cleanup.html).

Note: this is just the readme. The project home is here: http://claude-martin.ch/java-cleanup/ 
