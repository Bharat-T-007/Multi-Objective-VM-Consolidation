# Third-party attribution

This project uses CloudSim Plus 8.0.0 (`org.cloudsimplus:cloudsimplus`), an open-source cloud simulator. Its published Maven POM identifies the license as GPLv3. Project: https://cloudsimplus.org/ ; source: https://github.com/cloudsimplus/cloudsimplus . CloudSim Plus is credited to its authors and derives from the CloudSim research framework. Algorithm references include Deb et al. (2002), DOI 10.1109/4235.996017; Beloglazov and Buyya (2012), DOI 10.1002/cpe.1867; and Calheiros et al. (2011), DOI 10.1002/spe.995.

JUnit Jupiter 5.11.4 is used for testing. NumPy, Matplotlib and their pinned dependencies are used for offline analysis. Maven build plugins and transitive dependencies retain their respective licenses and authorship.

The Git repository contains project source and run scripts. Local tools, dependency caches, generated measurements and compiled executables are excluded through `.gitignore`. Maven and pip obtain dependencies using the supplied version declarations. Dependency artifacts and their notices remain available in the local caches after setup.
