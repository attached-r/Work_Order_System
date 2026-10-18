@echo off
set "JAVA_HOME=E:\Inteilj_Java\JDK17"
set "PATH=%JAVA_HOME%\bin;E:\Inteilj_Java\Maven\apache-maven-3.9.12\bin;C:\Windows\System32;C:\Windows"
cd /d "D:\ProjectBySelf\Work_Order_System\Agent\AgentWorkOrder"
call "E:\Inteilj_Java\Maven\apache-maven-3.9.12\bin\mvn.cmd" -B test -Dagent.llm.provider=mock -Dagent.rag.store=simple -Dagent.rag.embedding.provider=mock -Dagent.rag.ingest-on-startup=always > mvn_test.log 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> mvn_test.log
