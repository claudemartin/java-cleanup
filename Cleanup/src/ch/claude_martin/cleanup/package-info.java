/**
 * This package contains the interface {@link ch.claude_martin.cleanup.Cleanup}, which makes it 
 * easy to register code to clean up after some object has been removes by garbage collection.
 * It solves some problems that are common with finalizer blocks, but it has it's own pitfalls.
 * <p>Note that this can only be used with Java 8 or newer. Older versions of Java do not allow 
 * lambdas and there use of a "Finalizer Guardian" is probably more convenient.
 * 
 * <p>
 * Projekt Home: <a href="https://code.google.com/p/java-cleanup/">https://code.google.com/p/java-cleanup/</a>
 * 
 * <p>I provide this code as is, with no guarantees. The MIT license follows: 
 * 
 * <p>
 * The MIT License (MIT)
 * 
 * <p>
 * Copyright (c) 2014 Claude Martin Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package ch.claude_martin.cleanup;