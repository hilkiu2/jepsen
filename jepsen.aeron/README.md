# jepsen.aeron

A Clojure library designed to ... well, that part is up to you.

## Usage

Hello! Here is the README for the Aeron Cluster Jepsen test cases. To successfully be able to run this, you'll need to update any paths to the
Aeron Cluster code, as at the moment they are hardcoded to my personal ones. You'll also need to update the hostname of your machine on which
you are running the local cluster. These updates need to be made within /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron.clj and
/users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron/db.clj. 

To configure a test run, you need to update /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron.clj with the desired level of concurrency, 
the nemesis you wish to run (all avaialbe ones are listed and commented out, just uncomment which you wish to run), and you can also adjust the 
number of messages sent per second using the stagger field. 

You can run the test using ```lein run test --time-limit X --test-count Y```. time-limit is required, but test-count is optional, if you want to 
run the same test multiple times without needing to restart it yourself. The tests will run until there is an invalid analysis or the conclusion of the 
number of tests. 

If you're curious to see the specifics of how the nemeses work, see /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron/nemesis.clj
If you're curious to see the specifics of how the client work, see /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron/client.clj
If you're curious to see the specifics of how the model works that determines linearizability, see /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron/model.clj
If you're curious to see the specifics of how the system is set up and torn down, see /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron/db.clj

All these files come together to run the test within /users/hilkiu2/jepsen/jepsen.aeron/src/jepsen/aeron.clj, where the configuration are made for the test run.


## License

Copyright © 2025 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
