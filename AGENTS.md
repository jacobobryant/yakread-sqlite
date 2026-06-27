This project uses a snapshot testing approach that involves putting test cases
in .edn files. When you make changes and update the .edn files, you must not
commit the changes blindly: you should actually look at the files and see if any
changes in actual results make sense. If there was a change in the test result
that doesn't look right, you need to either fix the bug or fix the test case.
