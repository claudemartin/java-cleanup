package ch.claude_martin.cleanup;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * This is not a Unit-Test, but just an Example. The code is explained by comments.
 * 
 * Read the pros and cons also: {@link Cleanup}
 */
// The class should be final, because only valid objects should be used.
// For non-final classes you might want to consider using a "Finalizer Guardian"
public final class Example implements Cleanup {
  final OutputStream resource;
  static final Logger logger = Logger.getLogger(Example.class.getName());

  public Example() throws FileNotFoundException, IOException {
    super();
    // let's open some resource that we want closed whenever this is garbage collected:
    File tempFile = File.createTempFile("example", "tmp");
    this.resource = new FileOutputStream(tempFile);
    tempFile.deleteOnExit(); // This example shouldn't leave any files, even if it fails.
    // This could be a database connection or anything that should be closed.
    // Note that large arrays do not need to be cleaned up. Only things that can be closed.

    // Make sure the object is completely constructed and valid.

    // We can register some cleanup action here and the invoker of this constructor can add more code
    // later. But if this is called here it must be the last thing in this constructor. And as this
    // class should be final there is no child-constructor that could invalidate this object.
    this.registerCleanup((value) -> {
      try {
        // 'value' instead of 'this.resource', so no reference to 'this' is leaked:
        value.close();
      } catch (Exception e) {
        logger.warning(e.toString());
      }
    }, this.resource);
    // For convenience there is a method for auto-closeable resources:
    // this.registerAutoClose(this.resource);
    // The above does nearly the same.

    // I actually recommend this pattern:
    this.registerCleanup(Example::cleanup);
    // The actual method is implemented further below.
    // Note also that you can register as many cleanup routines as you want!
  }

  /**
   * We DO NOT use a finalizer!!
   * <p>
   * Joshua Bloch explains why:<br>
   * <a href="http://www.informit.com/articles/article.aspx?p=1216151&seqNum=7" >Creating and
   * Destroying Java Objects - Item 7: Avoid finalizers</a>
   * 
   * @see #cleanup()
   */
  protected void finalize() throws Throwable {
    // DON'T!
  }

  /**
   * An alternative for {@link #finalize()}. Must be static, void and without arguments. It's
   * basically like {@link Runnable#run()}, but static. The name doesn't matter, but 'cleanup' is
   * recommended.
   * <p>
   * This has a great benefit: It's impossible to have a reference to <tt>this</tt> inside a static
   * method. <br>
   * And the registration is also very simple and concise:<br>
   * {@code this.registerCleanup(Example::cleanup); }<br>
   * Another bonus is that you can add javadoc to a method. Try that on a lambda-expression.
   */
  protected static void cleanup() {
    // This comes close to a finalize-method.
    logger.info("An instance of Example was removed.");
  }

  /**
   * Run this in a console (without arguments). It will tell you how long an object existed until
   * cleanup was invoked.
   */
  public static void main(String[] args) throws Exception {
    // We can register global exception handlers:
    Cleanup.addExceptionHandler(ex -> logger.warning(ex.toString()));
    // A long that will be set to something larger than 0 on cleanup:
    final AtomicLong ms = new AtomicLong(0L);
    // An instance of the 'Example':
    Example example = new Example();
    example.registerCleanup((then) -> {
      // How long until this cleanup is done?
        final long nanos = System.nanoTime() - then;
        ms.set(nanos / 1_000_000L); // = milliseconds
      }, System.nanoTime()); // measured right after 'example' was created.

    // The cleanup action was not executed yet!
    if (ms.get() != 0)
      throw new RuntimeException("Something went wrong.");

    System.out.println("The object is created.");
    System.out.println("Hit <ENTER> to have it removed.");
    System.in.read();

    // We want it to be removed and cleaned up:
    example = null; // free to be removed by GC.
    // This is just for the test, as the cleanup-thread also has minimum priority:
    Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
    // Now we try to really get rid of it:
    for (int n = 0; n < 100 && ms.get() == 0; n++) {
      // Allow GC and CleanupDaemon to work:
      Thread.sleep(10);
      System.gc();
    }

    // Let's see if it worked:
    if (ms.get() <= 0) {
      System.err.println("Sadly this didn't work :-(");
    } else {
      System.out.format("Hooray, the resource was closed after about %,d milliseconds :-)",
          ms.get());
    }
  }
}
