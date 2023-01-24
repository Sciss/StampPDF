[![Build Status](https://github.com/Sciss/StampPDF/workflows/Scala%20CI/badge.svg?branch=main)](https://github.com/Sciss/StampPDF/actions?query=workflow%3A%22Scala+CI%22)

# StampPDF

A utility to stamp an image on a PDF, for example a scanned signature.

(C)opyright 2023 by Hanns Holger Rutz. All rights reserved. This project is released under the
[GNU Affero General Public License](https://github.com/Sciss/Rogues/blob/main/LICENSE) v3+ and
comes with absolutely no warranties.
To contact the author, send an e-mail to `contact at sciss.de`.

## requirements

This utility relies on PDFtk (`pdftk`) and ImageMagick (`convert`) being installed.

## building

Builds with sbt against Scala 3. See options: `sbt 'run --help'`. E.g.

    sbt 'run --input input.pdf --stamp stamp.jpg --ui'  # show GUI
